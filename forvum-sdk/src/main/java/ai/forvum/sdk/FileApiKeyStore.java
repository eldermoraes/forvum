package ai.forvum.sdk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A file-backed store for provider API keys under {@code $FORVUM_HOME/state/credentials/<providerId>}
 * (P2-10 #35). One opaque secret per file, plain text, owner-only ({@code 0600}; the directory
 * {@code 0700}) — the {@code CopilotCredentials} recipe generalized so the {@code forvum provider add}
 * wizard can persist a key and a key-based {@code ModelProvider} can read it back at {@code resolve()}
 * time, the "fixed code, configurable behavior" seam (CLAUDE.md section 1) without a recompile.
 *
 * <p>This lives in {@code forvum-sdk} (not the engine) so a Layer-3 provider — which may depend only on
 * {@code forvum-sdk} + {@code forvum-core}, never on {@code forvum-engine}'s {@code ForvumHome} — can use
 * it. The home is resolved self-contained from the {@code forvum.home} value the provider injects
 * (mirroring {@code ForvumHome.resolve} and {@code CopilotCredentials.resolveHome}); the app, which has
 * the {@code ForvumHome} bean, passes its {@code root()} directly. Pure JDK ({@code java.nio}), no
 * reflection — native-safe.
 *
 * <p>Precedence is the caller's concern: a key-based provider reads its {@code @ConfigProperty}
 * api-key first (env / {@code -D} / {@code application.properties} keep priority) and falls back to this
 * store only when that is blank, so an operator who exports {@code QUARKUS_LANGCHAIN4J_*_API_KEY}
 * overrides a stored file.
 */
public final class FileApiKeyStore {

    private static final String DEFAULT_HOME_DIR = ".forvum";
    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Set<PosixFilePermission> DIR_PERMS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMS = PosixFilePermissions.fromString("rw-------");
    /** Provider ids are extension ids (e.g. {@code anthropic}); confine the filename to defeat traversal. */
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9._-]+");

    private FileApiKeyStore() {
    }

    /**
     * Resolve {@code $FORVUM_HOME} from the {@code forvum.home} value a provider injects via
     * {@code @ConfigProperty} — present-and-non-blank wins, else {@code <user.home>/.forvum}. Always
     * absolute and normalized. Mirrors {@code ForvumHome.resolve} so the wizard and a provider agree on
     * the path without the provider depending on the engine.
     */
    public static Path resolveHome(Optional<String> configuredHome) {
        return configuredHome
                .filter(value -> !value.isBlank())
                .map(value -> Path.of(value).toAbsolutePath().normalize())
                .orElseGet(() -> Path.of(System.getProperty("user.home"))
                        .resolve(DEFAULT_HOME_DIR).toAbsolutePath().normalize());
    }

    /** The credential file for {@code providerId} under {@code home}; rejects a traversal-bearing id. */
    public static Path credentialFile(Path home, String providerId) {
        String id = providerId == null ? "" : providerId.strip().toLowerCase(Locale.ROOT);
        if (id.isEmpty() || !SAFE_ID.matcher(id).matches() || id.equals(".") || id.equals("..")) {
            throw new IllegalArgumentException(
                    "Invalid provider id '" + providerId + "': only [a-z0-9._-] are allowed (no path separators).");
        }
        return home.resolve("state").resolve("credentials").resolve(id);
    }

    /** The stored API key for {@code providerId}, or empty when no (non-blank) credential file exists. */
    public static Optional<String> read(Path home, String providerId) {
        Path file = credentialFile(home, providerId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String key = Files.readString(file, StandardCharsets.UTF_8).strip();
            return key.isBlank() ? Optional.empty() : Optional.of(key);
        } catch (IOException e) {
            // An unreadable credential file degrades to "no key" — the provider then surfaces the usual
            // missing-key error at chat() time rather than crashing the resolve.
            return Optional.empty();
        }
    }

    /**
     * Persist {@code key} for {@code providerId} owner-only ({@code 0600}), creating
     * {@code state/credentials/} owner-only ({@code 0700}) and re-tightening it if it already exists
     * (the {@code CopilotCredentials} note: {@code createDirectories} only applies the attribute to dirs
     * it creates).
     */
    public static void store(Path home, String providerId, String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("API key must be a non-blank value.");
        }
        Path file = credentialFile(home, providerId);
        Path dir = file.getParent();
        try {
            if (POSIX) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_PERMS));
                Files.setPosixFilePermissions(dir, DIR_PERMS);
            } else {
                Files.createDirectories(dir);
            }
            Files.writeString(file, key.strip(), StandardCharsets.UTF_8);
            if (POSIX) {
                Files.setPosixFilePermissions(file, FILE_PERMS);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write API key to " + file, e);
        }
    }
}
