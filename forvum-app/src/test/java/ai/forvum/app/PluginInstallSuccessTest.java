package ai.forvum.app;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The {@code forvum plugin install <coords>} SUCCESS path end-to-end through the CLI: a real resolve +
 * stream that prints the user-facing {@code "Installed <coords> -> <path>"} line and the {@code "Restart
 * Forvum ... load the new plugin"} follow-up. Kept hermetic — and offline — exactly like the engine's
 * {@code MavenPluginResolverTest}: a tiny JAR + POM laid out in Maven layout under a {@code @TempDir} is
 * served as a {@code file://} remote via the {@code forvum.plugins.repository-url} override, so no network
 * and no real {@code ~/.m2} are touched. {@code user.home} is redirected to a throwaway {@code .m2} for the
 * launch so the resolver's local cache writes land there, never in the developer's real {@code ~/.m2}.
 *
 * <p>Covers the JVM (fast-jar) branch of {@link PluginInstallCommand#call()} — {@code ImageMode.current()
 * == JVM} — and its "restart the fast-jar" message. The native ({@code NATIVE_RUN}) branch is NOT exercised
 * here on purpose: by design the Maven Resolver graph stays inert on the native classpath and is never
 * initialized in the binary (§6.2/§6.3), so a native {@code plugin install} is not a supported path to
 * resolve from the binary — only the message wording differs, and forcing the resolver to run natively
 * would contradict that inertness invariant.
 */
@QuarkusMainTest
@TestProfile(PluginInstallSuccessTest.HermeticRemoteProfile.class)
class PluginInstallSuccessTest {

    private static String savedUserHome;

    @BeforeAll
    static void redirectUserHomeForLocalCache() {
        savedUserHome = System.getProperty("user.home");
        // The resolver's local-cache dir is <user.home>/.m2/repository, read lazily at install() time; point
        // it at a throwaway dir so the test artifact never caches into the developer's real ~/.m2.
        System.setProperty("user.home", HermeticRemoteProfile.FAKE_USER_HOME.toString());
    }

    @AfterAll
    static void restoreUserHome() {
        if (savedUserHome != null) {
            System.setProperty("user.home", savedUserHome);
        } else {
            System.clearProperty("user.home");
        }
    }

    @Test
    void installPrintsTheInstalledCoordinateAndTheRestartInstruction(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("plugin", "install", HermeticRemoteProfile.COORDS);

        Assertions.assertEquals(0, result.exitCode(),
                () -> "a resolvable coordinate must exit 0; stderr: " + result.getErrorOutput());
        // "Installed <coords> -> <path>": the coordinate echoed back, and the JAR streamed into plugins/.
        Assertions.assertTrue(result.getOutput().contains("Installed " + HermeticRemoteProfile.COORDS + " -> "),
                () -> "must print 'Installed <coords> -> <path>'; got: " + result.getOutput());
        Assertions.assertTrue(
                result.getOutput().contains(HermeticRemoteProfile.ARTIFACT + "-"
                        + HermeticRemoteProfile.VERSION + ".jar"),
                () -> "the installed path must name the canonical artifactId-version.jar; got: "
                        + result.getOutput());
        // JVM (fast-jar) follow-up: tell the user to restart so ServiceLoader picks up the new plugin.
        Assertions.assertTrue(
                result.getOutput().contains("Restart Forvum (the fast-jar) to load the new plugin."),
                () -> "the JVM path must print the fast-jar restart instruction; got: " + result.getOutput());
    }

    /**
     * Seeds a {@code file://} remote holding {@code tiny-plugin-1.0.0.jar} + its POM, redirects the resolver
     * there via {@code forvum.plugins.repository-url}, and isolates {@code forvum.home} so the install's
     * {@code plugins/} writes do not leak into a sibling default-run test.
     */
    public static class HermeticRemoteProfile implements QuarkusTestProfile {

        static final String GROUP = "ai.forvum.test";
        static final String ARTIFACT = "tiny-plugin";
        static final String VERSION = "1.0.0";
        static final String COORDS = GROUP + ":" + ARTIFACT + ":" + VERSION;

        static final Path FORVUM_HOME = createTempDir("forvum-plugin-install-home");
        static final Path FAKE_USER_HOME = createTempDir("forvum-plugin-install-m2home");
        static final Path REMOTE = seedRemote(createTempDir("forvum-plugin-install-remote"));

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "forvum.home", FORVUM_HOME.toString(),
                    "forvum.plugins.repository-url", REMOTE.toUri().toString());
        }

        private static Path createTempDir(String prefix) {
            try {
                return Files.createTempDirectory(prefix);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static Path seedRemote(Path root) {
            try {
                Path dir = root.resolve(GROUP.replace('.', '/')).resolve(ARTIFACT).resolve(VERSION);
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(ARTIFACT + "-" + VERSION + ".jar"), "plugin-bytes");
                Files.writeString(dir.resolve(ARTIFACT + "-" + VERSION + ".pom"), pom());
                return root;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static String pom() {
            return """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>%s</groupId>
                      <artifactId>%s</artifactId>
                      <version>%s</version>
                      <packaging>jar</packaging>
                    </project>
                    """.formatted(GROUP, ARTIFACT, VERSION);
        }
    }
}
