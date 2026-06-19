package ai.forvum.engine.devui;

import ai.forvum.engine.config.ChangeType;
import ai.forvum.engine.config.ConfigLoader;
import ai.forvum.engine.config.ConfigurationChangedEvent;
import ai.forvum.engine.config.ForvumHome;
import ai.forvum.engine.doctor.ConfigDoctor;
import ai.forvum.engine.doctor.DoctorReport;
import ai.forvum.engine.doctor.Finding;
import ai.forvum.engine.doctor.Severity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The behavior behind the Dev UI live config editor (P3-6 #54, ULTRAPLAN section 7.2 item / §3.2 Dev UI
 * surface). Lists the user-editable {@code ~/.forvum/} config files, reads one for editing, and saves an
 * edit only after it VALIDATES — then fires the same {@link ConfigurationChangedEvent} the
 * {@link ai.forvum.engine.config.ConfigWatcher} would, so the engine hot-reloads the edited agent/cron
 * without a restart.
 *
 * <p><strong>Dev-mode-only surface, validated by the REAL oracle.</strong> This service is invoked only
 * from the dev-profile-gated {@code @Route} editor in {@code forvum-app} (the Quarkus Dev UI is fast-jar
 * dev-mode ONLY — an explicit, documented native carve-out, so nothing here enters the production/native
 * image surface). It does not re-implement parsing: validation runs through {@link ConfigDoctor}, which
 * reuses the M4 readers and the engine's typed binders as its oracle (P2-9). So a config the editor saves
 * is exactly a config the engine can load — there is no second, drifting schema definition.
 *
 * <p><strong>Save is validate-then-write-then-rollback.</strong> {@link #save} writes the candidate to the
 * target path, runs {@link ConfigDoctor} over the whole home, and inspects the findings for THAT file; if
 * any is an {@link Severity#ERROR} it restores the previous content (a new file is deleted) and reports the
 * findings without firing a change event — so a bad edit can never leave the engine with a broken config it
 * would then try to hot-reload. On success it fires the change event and returns the (possibly warning-only)
 * findings. {@link #validate} is the dry run: it writes to a throwaway sibling home, runs doctor, and
 * restores nothing — the on-disk config is never touched.
 *
 * <p>Plain final class (constructed per request, mirroring {@link ConfigDoctor}): the app supplies the
 * resolved {@link ForvumHome}, the {@link ConfigLoader}, the set of installed model-provider extension ids
 * (so a model ref pointing at an uninstalled provider is flagged, the {@code DoctorCommand} idiom), and a
 * {@code changeNotifier} the engine wires to {@code Event<ConfigurationChangedEvent>::fire}. All work is
 * blocking file IO on the calling (request) virtual thread — no {@code synchronized}, no reactive types.
 */
public final class ConfigEditorService {

    /**
     * The editable surface: a config subfolder (relative to {@code $FORVUM_HOME}) and the file suffixes that
     * are editable there. {@code config.json} (a single root file) is handled separately by {@link #files}.
     */
    private static final List<EditableDir> EDITABLE_DIRS = List.of(
            new EditableDir("agents", List.of(".json", ".md")),
            new EditableDir("identities", List.of(".json")),
            new EditableDir("channels", List.of(".json")),
            new EditableDir("crons", List.of(".json")),
            new EditableDir("roles", List.of(".json")),
            new EditableDir("devices", List.of(".json")),
            new EditableDir("mcp-servers", List.of(".json")),
            new EditableDir("skills", List.of(".md")),
            new EditableDir("tools", List.of(".json")));

    private final ForvumHome home;
    private final ConfigLoader loader;
    private final Set<String> knownProviders;
    private final Consumer<ConfigurationChangedEvent> changeNotifier;

    public ConfigEditorService(ForvumHome home, ConfigLoader loader, Set<String> knownProviders,
                               Consumer<ConfigurationChangedEvent> changeNotifier) {
        this.home = home;
        this.loader = loader;
        this.knownProviders = Set.copyOf(knownProviders);
        this.changeNotifier = changeNotifier;
    }

    /**
     * The editable config files that currently exist under {@code $FORVUM_HOME}, as {@code $FORVUM_HOME}-
     * relative paths (forward-slash separated), sorted. {@code config.json} is included when present.
     */
    public List<String> files() {
        List<String> paths = new ArrayList<>();
        if (Files.isRegularFile(home.configFile())) {
            paths.add("config.json");
        }
        for (EditableDir dir : EDITABLE_DIRS) {
            Path dirPath = home.root().resolve(dir.name());
            for (String suffix : dir.suffixes()) {
                for (String id : loader.listIds(dirPath, suffix)) {
                    paths.add(dir.name() + "/" + id + suffix);
                }
            }
        }
        return paths.stream().distinct().sorted().toList();
    }

    /**
     * Read the current content of an editable config file. Empty when the file does not exist (a new file
     * the editor is about to create). Throws {@link IllegalArgumentException} for a path that escapes the
     * editable surface (traversal, an unknown subfolder).
     */
    public Optional<String> read(String relativePath) {
        Path file = resolveEditable(relativePath);
        return loader.readText(file);
    }

    /**
     * Dry-run validation of a candidate edit: report what {@link ConfigDoctor} would say WITHOUT touching the
     * on-disk config. The candidate is staged into a throwaway copy of {@code $FORVUM_HOME} so cross-file
     * checks (a cron's {@code agentId} referencing an agent, a model ref naming a provider) see the rest of
     * the real config. Returns only the findings whose location is the edited file.
     */
    public List<Finding> validate(String relativePath, String candidateContent) {
        Path target = resolveEditable(relativePath);
        Path scratchHome = stageScratchHome();
        try {
            Path scratchFile = scratchHome.resolve(home.root().relativize(target));
            writeFile(scratchFile, candidateContent);
            ForvumHome scratchForvumHome = new ForvumHome(Optional.of(scratchHome.toString()));
            DoctorReport report = new ConfigDoctor(scratchForvumHome, loader, knownProviders).check();
            return findingsFor(report, relativePath);
        } finally {
            deleteRecursively(scratchHome);
        }
    }

    /**
     * Validate and, when there is no {@link Severity#ERROR}, persist the candidate and fire a
     * {@link ConfigurationChangedEvent} so the engine hot-reloads. On an ERROR the previous content is
     * restored (or a newly-created file deleted) and no event is fired. The returned {@link SaveResult}
     * reports whether it was saved plus the findings for the edited file.
     */
    public SaveResult save(String relativePath, String candidateContent) {
        Path target = resolveEditable(relativePath);
        boolean existed = Files.isRegularFile(target);
        String previousContent = existed ? loader.readText(target).orElse("") : null;

        writeFile(target, candidateContent);

        DoctorReport report = new ConfigDoctor(home, loader, knownProviders).check();
        List<Finding> findings = findingsFor(report, relativePath);
        boolean hasError = findings.stream().anyMatch(f -> f.severity() == Severity.ERROR);

        if (hasError) {
            if (existed) {
                writeFile(target, previousContent);
            } else {
                deleteQuietly(target);
            }
            return new SaveResult(false, findings);
        }

        changeNotifier.accept(new ConfigurationChangedEvent(
                home.root().relativize(target), existed ? ChangeType.MODIFIED : ChangeType.CREATED));
        return new SaveResult(true, findings);
    }

    /** Findings whose location is the edited file (doctor uses {@code $FORVUM_HOME}-relative locations). */
    private static List<Finding> findingsFor(DoctorReport report, String relativePath) {
        return report.findings().stream()
                .filter(f -> f.location().equals(relativePath))
                .toList();
    }

    /**
     * Resolve a {@code $FORVUM_HOME}-relative path, confined to the editable subfolders / {@code config.json}.
     * Rejects traversal ({@code ..}), absolute paths, and any directory not in {@link #EDITABLE_DIRS}.
     */
    private Path resolveEditable(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("config path must be non-blank");
        }
        if ("config.json".equals(relativePath)) {
            return home.configFile();
        }
        Path root = home.root();
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("config path escapes $FORVUM_HOME: " + relativePath);
        }
        Path parent = resolved.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("not an editable config path: " + relativePath);
        }
        String subfolder = root.relativize(parent).toString().replace('\\', '/');
        boolean editable = EDITABLE_DIRS.stream().anyMatch(d -> d.name().equals(subfolder)
                && d.suffixes().stream().anyMatch(resolved.getFileName().toString()::endsWith));
        if (!editable) {
            throw new IllegalArgumentException("not an editable config path: " + relativePath);
        }
        return resolved;
    }

    /** Copy the real home into a throwaway sibling temp dir for dry-run validation. */
    private Path stageScratchHome() {
        try {
            Path scratch = Files.createTempDirectory("forvum-devui-validate");
            if (Files.isDirectory(home.root())) {
                copyRecursively(home.root(), scratch);
            }
            return scratch;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to stage a validation copy of " + home.root(), e);
        }
    }

    private static void copyRecursively(Path source, Path destination) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path relative = source.relativize(path);
                Path target = destination.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }

    private static void writeFile(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content == null ? "" : content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write config file: " + file, e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to remove config file: " + file, e);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException("Failed to clean validation copy: " + path, e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to clean validation copy: " + dir, e);
        }
    }

    /** A config subfolder and the file suffixes editable within it. */
    private record EditableDir(String name, List<String> suffixes) {
    }

    /** The outcome of a {@link #save}: whether the candidate was persisted, and the findings for the file. */
    public record SaveResult(boolean saved, List<Finding> findings) {

        public SaveResult {
            findings = List.copyOf(findings);
        }
    }
}
