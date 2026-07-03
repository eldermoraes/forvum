package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;
import io.quarkus.test.junit.main.QuarkusMainLauncher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/**
 * Native parity for owner-only state permission enforcement (#173, DR-6c [6c-DP-13]). Drives the produced
 * binary OUT-OF-PROCESS: a {@code replay <missing-session>} launch boots the full persistence path, which on
 * boot creates {@code <home>/state/} and migrates {@code forvum.sqlite} (exit 1, session absent) — so
 * {@code StateDirInitializer}'s create + harden runs inside the native image. Asserts the state directory is
 * {@code 0700} and the database {@code 0600}, proving {@code Files.setPosixFilePermissions}/{@code walkFileTree}
 * behave natively (a JVM test cannot cover the native image).
 *
 * <p>Deterministic and offline (no live LLM, mirroring {@link SessionReplayNativeIT}), so it carries NO
 * {@code @Tag("live")} and runs in the default native leg. {@code replay} is deliberately not a
 * {@code CommandMode} one-shot, so it boots Flyway/Panache — the same {@code forvum.home} propagation the
 * replay/doctor native ITs rely on.
 */
@QuarkusMainIntegrationTest
@TestProfile(StatePermissionsNativeIT.PermsHomeProfile.class)
class StatePermissionsNativeIT {

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    @Test
    void nativeBootCreatesAnOwnerOnlyStateDirectoryAndDatabase(QuarkusMainLauncher launcher) throws IOException {
        assumeTrue(POSIX, "POSIX-only owner-only assertion");
        // Boot the binary on a command that migrates the schema (unseeded session -> not-found, exit 1),
        // creating and hardening <home>/state and forvum.sqlite natively.
        LaunchResult boot = launcher.launch("replay", "cli:absent-session");
        assertEquals(1, boot.exitCode(),
                () -> "an unseeded replay must migrate then report not-found (exit 1); stderr: "
                        + boot.getErrorOutput());

        Path state = PermsHomeProfile.HOME.resolve("state");
        Path database = state.resolve("forvum.sqlite");

        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(state),
                () -> "the native binary must create state/ owner-only (0700), independent of umask; was "
                        + safeMode(state));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(database),
                () -> "the native binary must harden the SQLite database to owner-only (0600); was "
                        + safeMode(database));
    }

    private static String safeMode(Path path) {
        try {
            return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
        } catch (IOException e) {
            return "<unreadable>";
        }
    }

    /** Points {@code forvum.home} at a throwaway temp dir; routes logs to stderr for a clean stdout. */
    public static class PermsHomeProfile implements QuarkusTestProfile {

        static final Path HOME = createHome();

        private static Path createHome() {
            try {
                return Files.createTempDirectory("forvum-native-perms-home");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "forvum.home", HOME.toString(),
                    "quarkus.log.console.stderr", "true");
        }
    }
}
