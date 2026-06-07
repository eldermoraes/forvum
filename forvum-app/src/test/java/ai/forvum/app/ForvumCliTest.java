package ai.forvum.app;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/**
 * The M20 picocli command-mode surface: {@code --help} and {@code --version} print and exit 0 (handled by
 * picocli before the channel dispatch), and {@code init} scaffolds a runnable {@code ~/.forvum}. Uses its
 * OWN temp home (distinct from {@link ForvumApplicationTest}'s) so {@code init} writing config does not
 * flip a sibling default-run test into server/interactive mode ([M7] shared-home discipline). These are
 * one-shot commands, so the cold-start path skips Flyway + the watcher ({@code CommandMode}).
 */
@QuarkusMainTest
@TestProfile(ForvumCliTest.CliHomeProfile.class)
class ForvumCliTest {

    @Test
    @Launch({"--help"})
    void helpPrintsUsageAndExitsZero(LaunchResult result) {
        Assertions.assertEquals(0, result.exitCode());
        Assertions.assertTrue(result.getOutput().contains("Usage: forvum"),
                () -> "Expected picocli usage for 'forvum', got: " + result.getOutput());
        Assertions.assertTrue(result.getOutput().contains("init"),
                () -> "Expected the 'init' subcommand listed in help, got: " + result.getOutput());
    }

    @Test
    @Launch({"--version"})
    void versionPrintsAndExitsZero(LaunchResult result) {
        Assertions.assertEquals(0, result.exitCode());
        Assertions.assertTrue(result.getOutput().contains("Forvum 0.1.0-SNAPSHOT"),
                () -> "Expected the version string, got: " + result.getOutput());
    }

    @Test
    @Launch({"init"})
    void initScaffoldsARunnableHome(LaunchResult result) throws IOException {
        Assertions.assertEquals(0, result.exitCode());
        Assertions.assertTrue(result.getOutput().contains("Initialized"),
                () -> "Expected an init confirmation, got: " + result.getOutput());
        Path home = CliHomeProfile.HOME;
        Assertions.assertTrue(Files.exists(home.resolve("agents").resolve("main.md")), "agents/main.md scaffolded");
        Assertions.assertTrue(Files.exists(home.resolve("agents").resolve("main.json")), "agents/main.json scaffolded");
        Assertions.assertTrue(Files.exists(home.resolve("identities").resolve("default.json")), "identities/default.json scaffolded");
        Assertions.assertTrue(Files.exists(home.resolve("channels").resolve("tui.json")), "channels/tui.json scaffolded");

        // The tree later holds credentials (e.g. channels/telegram.json botToken), so init must restrict
        // it to the owner on POSIX (0700 dirs / 0600 files), not the world-readable umask default.
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Assertions.assertEquals(PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(home.resolve("agents")), "scaffolded dir must be 0700");
            Assertions.assertEquals(PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(home.resolve("agents").resolve("main.json")),
                    "scaffolded file must be 0600");
        }
    }

    /** A throwaway {@code $FORVUM_HOME} that {@code init} may write into without polluting other tests. */
    public static class CliHomeProfile implements QuarkusTestProfile {

        static final Path HOME = create();

        private static Path create() {
            try {
                return Files.createTempDirectory("forvum-cli-home");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", HOME.toString());
        }
    }
}
