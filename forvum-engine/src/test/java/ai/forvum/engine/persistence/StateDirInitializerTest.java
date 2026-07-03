package ai.forvum.engine.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Unit test for {@link StateDirInitializer} — the graceful-boot dir-ensure and the owner-only permission
 * hardening (#173, materializing DR-6c [6c-DP-13]). Pure {@code *Test}, no Quarkus boot; the POSIX-mode
 * assertions run only on a POSIX filesystem ({@link #POSIX}).
 */
class StateDirInitializerTest {

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Set<PosixFilePermission> OWNER_ONLY_DIR = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> WORLD_WRITABLE = PosixFilePermissions.fromString("rwxrwxrwx");
    private static final Set<PosixFilePermission> WORLD_READABLE_FILE =
            PosixFilePermissions.fromString("rw-rw-rw-");

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

    @Test
    void createsStateDirectoryOwnerOnlyOnPosix(@TempDir Path tmp) throws IOException {
        assumeTrue(POSIX, "POSIX-only owner-only assertion");
        Path state = tmp.resolve("state");

        assertTrue(StateDirInitializer.ensureStateDir(state));

        // Exactly 0700 — no group/other bits could have leaked from a permissive process umask (owner-only
        // perms carry no g/o bits for the umask to strip, so this mode is umask-independent by construction).
        assertEquals(OWNER_ONLY_DIR, Files.getPosixFilePermissions(state),
                "a first boot must create state/ owner-only (0700), independent of umask");
    }

    @Test
    void repairsAPreExistingWorldAccessibleDirectoryToOwnerOnly(@TempDir Path tmp) throws IOException {
        assumeTrue(POSIX, "POSIX-only owner-only assertion");
        Path state = tmp.resolve("state");
        // Simulate a state/ left world-accessible by a permissive umask on an earlier boot.
        Files.createDirectories(state);
        Files.setPosixFilePermissions(state, WORLD_WRITABLE);

        assertTrue(StateDirInitializer.ensureStateDir(state));

        assertEquals(OWNER_ONLY_DIR, Files.getPosixFilePermissions(state),
                "an existing world-accessible state/ must be repaired to owner-only (0700)");
    }

    @Test
    void rejectsASymlinkedStateDirectory(@TempDir Path tmp) throws IOException {
        assumeTrue(POSIX, "POSIX-only symlink assertion");
        Path real = tmp.resolve("real");
        Files.createDirectories(real);
        Files.setPosixFilePermissions(real, WORLD_WRITABLE);
        Path state = tmp.resolve("state");
        Files.createSymbolicLink(state, real);

        // A symlink where state/ is expected is a path-substitution vector — reject (persistence unavailable),
        // never create or harden through it.
        assertFalse(StateDirInitializer.ensureStateDir(state),
                "a symlinked state path must be rejected");
        assertEquals(WORLD_WRITABLE, Files.getPosixFilePermissions(real),
                "the symlink target must not be hardened through the link");
    }

    @Test
    void hardenStateFilesMakesDatabaseAndSidecarsOwnerOnly(@TempDir Path tmp) throws IOException {
        assumeTrue(POSIX, "POSIX-only owner-only assertion");
        Path state = tmp.resolve("state");
        Files.createDirectories(state);
        Path db = writeWorldReadable(state.resolve("forvum.sqlite"));
        Path wal = writeWorldReadable(state.resolve("forvum.sqlite-wal"));
        Path shm = writeWorldReadable(state.resolve("forvum.sqlite-shm"));

        StateDirInitializer.hardenStateFiles(state);

        assertEquals(OWNER_ONLY_FILE, Files.getPosixFilePermissions(db), "the database must be owner-only (0600)");
        assertEquals(OWNER_ONLY_FILE, Files.getPosixFilePermissions(wal), "the WAL sidecar must be owner-only");
        assertEquals(OWNER_ONLY_FILE, Files.getPosixFilePermissions(shm), "the SHM sidecar must be owner-only");
        assertEquals(OWNER_ONLY_DIR, Files.getPosixFilePermissions(state), "state/ must stay owner-only (0700)");
    }

    @Test
    void hardenStateFilesHardensNestedSubdirectoriesAndTheirFiles(@TempDir Path tmp) throws IOException {
        assumeTrue(POSIX, "POSIX-only owner-only assertion");
        Path state = tmp.resolve("state");
        Path credentials = state.resolve("credentials");
        Files.createDirectories(credentials);
        Files.setPosixFilePermissions(credentials, WORLD_WRITABLE);
        Path secret = writeWorldReadable(credentials.resolve("github-copilot.json"));

        StateDirInitializer.hardenStateFiles(state);

        assertEquals(OWNER_ONLY_DIR, Files.getPosixFilePermissions(credentials),
                "a nested state subdirectory must be hardened to owner-only");
        assertEquals(OWNER_ONLY_FILE, Files.getPosixFilePermissions(secret),
                "a file in a nested state subdirectory must be owner-only");
    }

    @Test
    void hardenStateFilesDoesNotFollowSymlinks(@TempDir Path tmp) throws IOException {
        assumeTrue(POSIX, "POSIX-only symlink assertion");
        Path outside = writeWorldReadable(tmp.resolve("outside"));
        Path state = tmp.resolve("state");
        Files.createDirectories(state);
        Files.createSymbolicLink(state.resolve("link"), outside);

        StateDirInitializer.hardenStateFiles(state);

        assertEquals(WORLD_READABLE_FILE, Files.getPosixFilePermissions(outside),
                "hardening must not chmod through a symlink to a file outside state/");
    }

    @Test
    void nonPosixBranchCreatesDirectoryWithoutHardening(@TempDir Path tmp) throws IOException {
        // Exercise the non-POSIX code path deterministically on a POSIX host: with posix=false the method
        // must create the directory EXACTLY like a plain Files.createDirectories — never applying owner-only
        // perms. Asserting equality with a plain-created reference locks that contract: a regression that
        // ignored the flag and always hardened would make state/ 0700 while the reference keeps the umask
        // default, failing this assertion under any umask that leaves a group/other bit.
        Path reference = tmp.resolve("reference");
        Files.createDirectories(reference);
        Path state = tmp.resolve("state");

        assertTrue(StateDirInitializer.ensureStateDir(state, false));
        assertTrue(Files.isDirectory(state));
        if (POSIX) {
            assertEquals(Files.getPosixFilePermissions(reference), Files.getPosixFilePermissions(state),
                    "the non-POSIX branch must not apply owner-only perms — it must behave like a plain create");
        }
        assertDoesNotThrow(() -> StateDirInitializer.hardenStateFiles(state, false));
    }

    @Test
    void concurrentInitializationAllSucceedAndDirectoryIsOwnerOnly(@TempDir Path tmp) throws Exception {
        assumeTrue(POSIX, "POSIX-only owner-only assertion");
        Path state = tmp.resolve("state");
        int threads = 8;

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Boolean>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> StateDirInitializer.ensureStateDir(state));
            }
            for (Future<Boolean> result : pool.invokeAll(tasks)) {
                assertTrue(result.get(), "every concurrent ensureStateDir must succeed");
            }
        }

        assertEquals(OWNER_ONLY_DIR, Files.getPosixFilePermissions(state),
                "concurrent initialization must converge on an owner-only state/");
    }

    private static Path writeWorldReadable(Path file) throws IOException {
        Files.writeString(file, "x");
        Files.setPosixFilePermissions(file, WORLD_READABLE_FILE);
        return file;
    }
}
