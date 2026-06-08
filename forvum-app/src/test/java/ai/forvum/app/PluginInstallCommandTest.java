package ai.forvum.app;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The {@code forvum plugin install <coords>} CLI surface (P2-6) is wired into {@link RootCommand} and its
 * argument parsing/error handling behaves. These checks stay hermetic — they exercise the picocli wiring
 * (help listing, required-parameter validation, malformed-coordinate handling) without resolving a real
 * artifact over the network. The end-to-end resolve+stream path is covered hermetically in the engine's
 * {@code MavenPluginResolverTest} ({@code file://} remote).
 *
 * <p>Uses its OWN temp home so the {@code plugins/} dir a future install writes cannot leak into a sibling
 * default-run test (shared-home discipline). {@code plugin} is a {@code CommandMode} one-shot, so these
 * launches skip the Flyway/watcher boot.
 */
@QuarkusMainTest
@TestProfile(PluginInstallCommandTest.PluginHomeProfile.class)
class PluginInstallCommandTest {

    @Test
    void rootHelpListsThePluginSubcommand(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("--help");
        Assertions.assertEquals(0, result.exitCode());
        Assertions.assertTrue(result.getOutput().contains("plugin"),
                () -> "the root --help must list the 'plugin' subcommand; got: " + result.getOutput());
    }

    @Test
    void pluginHelpListsTheInstallSubcommand(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("plugin", "--help");
        Assertions.assertEquals(0, result.exitCode(),
                () -> "'plugin --help' must exit 0; stderr: " + result.getErrorOutput());
        Assertions.assertTrue(result.getOutput().contains("install"),
                () -> "'plugin --help' must list the 'install' subcommand; got: " + result.getOutput());
    }

    @Test
    void installWithoutCoordinatesIsAUsageError(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("plugin", "install");
        Assertions.assertNotEquals(0, result.exitCode(),
                () -> "'plugin install' with no coordinate must be a usage error; stdout: "
                        + result.getOutput() + "; stderr: " + result.getErrorOutput());
        Assertions.assertTrue(result.getErrorOutput().contains("<coords>"),
                () -> "the usage error must name the missing <coords> parameter; got: "
                        + result.getErrorOutput());
    }

    @Test
    void installWithAMalformedCoordinateExitsOneWithADiagnostic(QuarkusMainLauncher launcher) {
        // 'not-a-coordinate' fails at parse time in the resolver (no network), so this stays hermetic.
        LaunchResult result = launcher.launch("plugin", "install", "not-a-coordinate");
        Assertions.assertEquals(1, result.exitCode(),
                () -> "a malformed coordinate must exit 1; stdout: " + result.getOutput()
                        + "; stderr: " + result.getErrorOutput());
        Assertions.assertTrue(result.getErrorOutput().contains("Plugin install failed"),
                () -> "the failure must print a diagnostic on stderr; got: " + result.getErrorOutput());
    }

    /** A throwaway {@code $FORVUM_HOME} so an install's {@code plugins/} writes do not pollute other tests. */
    public static class PluginHomeProfile implements QuarkusTestProfile {

        static final Path HOME = create();

        private static Path create() {
            try {
                return Files.createTempDirectory("forvum-plugin-home");
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
