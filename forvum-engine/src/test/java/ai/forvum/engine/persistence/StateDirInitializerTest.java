package ai.forvum.engine.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unit test for {@link StateDirInitializer#ensureStateDir(Path)} — the graceful-boot dir-ensure.
 * Pure {@code *Test}, no Quarkus boot.
 */
class StateDirInitializerTest {

    @Test
    void createsStateDirectory(@TempDir Path tmp) {
        Path state = tmp.resolve("state");

        StateDirInitializer.ensureStateDir(state);

        assertTrue(Files.isDirectory(state));
    }

    @Test
    void isIdempotentWhenAlreadyPresent(@TempDir Path tmp) throws IOException {
        Path state = tmp.resolve("state");
        Files.createDirectories(state);

        assertDoesNotThrow(() -> StateDirInitializer.ensureStateDir(state));
        assertTrue(Files.isDirectory(state));
    }

    @Test
    void warnsAndDoesNotThrowWhenPathIsBlockedByAFile(@TempDir Path tmp) throws IOException {
        // A regular file where a directory parent is needed makes createDirectories fail
        // deterministically (independent of filesystem permissions / running as root).
        Path blocker = tmp.resolve("blocker");
        Files.writeString(blocker, "x");
        Path state = blocker.resolve("state");

        assertDoesNotThrow(() -> StateDirInitializer.ensureStateDir(state));
        assertFalse(Files.isDirectory(state));
    }
}
