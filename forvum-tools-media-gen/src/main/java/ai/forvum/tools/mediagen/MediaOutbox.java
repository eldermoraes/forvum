package ai.forvum.tools.mediagen;

import ai.forvum.sdk.GeneratedMedia;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Persists one {@link GeneratedMedia} asset under the workspace {@code media/} outbox (#187): ensure
 * {@code <workspace>/media/}, write the bytes to a temp file, atomically move it into place, and return
 * the workspace-relative path plus the byte size (the {@code TtsSynthesizer} write discipline). Output
 * naming is generated-unique ({@code <kind>-<yyyyMMdd-HHmmss>-<8 hex random>.<ext>}) so collisions are
 * impossible by construction and there is NO model-supplied output path to confine. Every failure is a
 * {@link MediaGenException} (the engine audits {@code error} and renders the message to the model).
 *
 * <p>Pure orchestration (Quarkus-free, no CDI): the workspace root is a parameter, so it is directly
 * unit-testable against a {@code @TempDir}.
 */
final class MediaOutbox {

    /** The workspace subdirectory every generated asset is written to (fixed, no config surface). */
    static final String MEDIA_SUBDIR = "media";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private MediaOutbox() {
    }

    /**
     * Write {@code media}'s bytes under {@code <workspaceRoot>/media/} and return the result message for
     * the model (workspace-relative + absolute path, byte size).
     */
    static String write(Path workspaceRoot, GeneratedMedia media) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path mediaDir = root.resolve(MEDIA_SUBDIR);
        Path temp;
        try {
            Files.createDirectories(mediaDir);
            temp = Files.createTempFile(mediaDir, "gen-", ".tmp");
        } catch (IOException e) {
            throw new MediaGenException("Could not prepare the media output directory " + mediaDir
                    + ": " + e.getMessage());
        }
        try {
            try {
                Files.write(temp, media.data());
            } catch (IOException e) {
                throw new MediaGenException("Could not write the generated "
                        + media.kind().name().toLowerCase(Locale.ROOT) + " to " + mediaDir + ": "
                        + e.getMessage());
            }
            Path outFile = mediaDir.resolve(generatedName(media));
            move(temp, outFile);
            Path relative = root.relativize(outFile);
            return "wrote " + media.data().length + " bytes to " + relative
                    + " (absolute: " + outFile + ").";
        } finally {
            deleteQuietly(temp); // no-op after the successful atomic move
        }
    }

    /**
     * The generated, collision-free output file name: {@code <kind>-<yyyyMMdd-HHmmss>-<8 hex>.<ext>}. The
     * suffix is the first 8 hex chars of a random {@link UUID} — native-safe (unlike a static
     * {@code SecureRandom} field, which native-image rejects in the image heap for its cached seed).
     */
    private static String generatedName(GeneratedMedia media) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return media.kind().name().toLowerCase(Locale.ROOT) + "-" + LocalDateTime.now().format(STAMP)
                + "-" + suffix + "." + media.fileExtension();
    }

    /** Atomically move {@code temp} to {@code out}, falling back to a plain replace where unsupported. */
    private static void move(Path temp, Path out) {
        try {
            Files.move(temp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            try {
                Files.move(temp, out, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new MediaGenException("Could not write the generated asset " + out + ": "
                        + e.getMessage());
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup; never fatal.
        }
    }
}
