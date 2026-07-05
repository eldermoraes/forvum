package ai.forvum.app;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * The {@code forvum plugin install <coords>} INSTALL-SIDE failure path end-to-end through the CLI (#171):
 * resolution succeeds against a hermetic {@code file://} remote (valid {@code .jar.sha1}), but the install
 * is rejected because {@code $FORVUM_HOME/plugins} is a pre-planted SYMLINK — the engine throws
 * {@code PluginInstallException} and {@link PluginInstallCommand} must catch it into the clean
 * {@code "Plugin install failed: ..."} exit-1 diagnostic, never a raw stack trace. This is the test that
 * pins the CLI's {@code PluginInstallException} multi-catch arm: reverting the catch to
 * {@code PluginResolutionException} alone lets the exception escape to picocli's default handler (a stack
 * trace, no diagnostic line) and this test goes red.
 *
 * <p>Uses its OWN {@code @QuarkusMainTest} profile (fresh {@code forvum.home}) so the planted symlink can
 * never leak into a sibling test's shared home ([M7] shared-static-home discipline). {@code user.home} is
 * redirected so the successful resolution's local-cache write never touches the developer's real
 * {@code ~/.m2}.
 */
@QuarkusMainTest
@TestProfile(PluginInstallFailureTest.SymlinkedPluginsHomeProfile.class)
class PluginInstallFailureTest {

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private static String savedUserHome;

    @BeforeAll
    static void redirectUserHomeForLocalCache() {
        savedUserHome = System.getProperty("user.home");
        System.setProperty("user.home", SymlinkedPluginsHomeProfile.FAKE_USER_HOME.toString());
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
    void installIntoASymlinkedPluginsDirFailsWithACleanDiagnostic(QuarkusMainLauncher launcher)
            throws IOException {
        Assumptions.assumeTrue(POSIX, "symlink planting requires a POSIX filesystem");
        // Plant the attack shape: $FORVUM_HOME/plugins is a symlink to a victim directory.
        Path home = SymlinkedPluginsHomeProfile.FORVUM_HOME;
        Path victim = home.resolve("victim-dir");
        Files.createDirectories(victim);
        Path plugins = home.resolve("plugins");
        if (!Files.exists(plugins)) {
            Files.createSymbolicLink(plugins, victim);
        }

        LaunchResult result = launcher.launch("plugin", "install", SymlinkedPluginsHomeProfile.COORDS);

        Assertions.assertEquals(1, result.exitCode(),
                () -> "an install-side rejection must exit 1; stdout: " + result.getOutput()
                        + "; stderr: " + result.getErrorOutput());
        Assertions.assertTrue(result.getErrorOutput().contains("Plugin install failed:"),
                () -> "the install-side failure must print the clean CLI diagnostic (not a stack trace); "
                        + "got: " + result.getErrorOutput());
        // The victim directory must not have received the JAR through the link.
        try (var entries = Files.list(victim)) {
            Assertions.assertTrue(entries.findAny().isEmpty(),
                    "the symlink target must not be written through");
        }
    }

    /**
     * A fresh {@code $FORVUM_HOME} (the test plants {@code plugins} as a symlink inside it), a hermetic
     * {@code file://} remote with a VALID {@code .jar.sha1} (resolution must SUCCEED so the failure is
     * install-side), and a throwaway {@code user.home} for the resolver's local cache.
     */
    public static class SymlinkedPluginsHomeProfile implements QuarkusTestProfile {

        static final String GROUP = "ai.forvum.test";
        static final String ARTIFACT = "tiny-plugin";
        static final String VERSION = "1.0.0";
        static final String COORDS = GROUP + ":" + ARTIFACT + ":" + VERSION;

        static final Path FORVUM_HOME = createTempDir("forvum-plugin-fail-home");
        static final Path FAKE_USER_HOME = createTempDir("forvum-plugin-fail-m2home");
        static final Path REMOTE = seedRemote(createTempDir("forvum-plugin-fail-remote"));

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
                String jarName = ARTIFACT + "-" + VERSION + ".jar";
                String bytes = "plugin-bytes";
                Files.writeString(dir.resolve(jarName), bytes);
                // Valid checksum: resolution must pass the #171 strict policy so the failure is install-side.
                Files.writeString(dir.resolve(jarName + ".sha1"), sha1Hex(bytes));
                Files.writeString(dir.resolve(ARTIFACT + "-" + VERSION + ".pom"), pom());
                return root;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static String sha1Hex(String content) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-1")
                        .digest(content.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-1 must be available", e);
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
