package ai.forvum.app;

import ai.forvum.engine.config.ForvumHome;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The CLI-side read/write/list surface for {@code ~/.forvum/devices/<id>.json} (P2-PAIR-SCOPE #44),
 * mirroring {@code McpAddCommand}'s owner-only ({@code 0600}) file recipe. The engine reads devices
 * through its own {@code DeviceReader}/{@code DeviceSpecReader} at the turn entry and in {@code doctor};
 * this store is the {@code forvum pair}/{@code forvum devices} write+list path, parsing through the SAME
 * {@code DeviceSpecReader} so there is no second schema. Reads/writes preserve unknown JSON fields (it
 * edits the parsed {@link ObjectNode}), and a device id is validated against path traversal.
 */
@ApplicationScoped
public class DeviceConfigStore {

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Set<PosixFilePermission> DIR_PERMS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMS = PosixFilePermissions.fromString("rw-------");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]+");

    @Inject
    ForvumHome home;

    /** The device ids declared under {@code devices/} (the {@code .json} stems), sorted. */
    public List<String> ids() {
        Path dir = home.devices();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The parsed {@code devices/<id>.json} as a mutable JSON object, or empty when the file is absent. */
    public Optional<ObjectNode> read(String id) {
        Path file = fileOf(id);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of((ObjectNode) MAPPER.readTree(Files.readString(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Write {@code node} to {@code devices/<id>.json} owner-only (0600), creating the dir owner-only. */
    public void write(String id, ObjectNode node) {
        Path dir = home.devices();
        Path file = fileOf(id);
        try {
            if (POSIX) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_PERMS));
            } else {
                Files.createDirectories(dir);
            }
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node));
            if (POSIX) {
                Files.setPosixFilePermissions(file, FILE_PERMS);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path fileOf(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches() || id.equals(".") || id.equals("..")) {
            throw new IllegalArgumentException(
                    "Invalid device id '" + id + "': only [A-Za-z0-9._-] are allowed (no path separators).");
        }
        return home.devices().resolve(id + ".json");
    }
}
