package ai.forvum.engine.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.aether.repository.RemoteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Drives {@link MavenPluginResolver} against a hermetic {@code file://} remote repository — a tiny JAR +
 * its POM laid out in Maven layout in a {@code @TempDir} — so the test resolves and streams without any
 * network or {@code ~/.m2} dependence. Asserts the resolved JAR lands in the {@code plugins/} dir with the
 * resolver's canonical {@code artifactId-version.jar} filename, and that malformed/missing coordinates
 * surface as a {@link PluginResolutionException}. Plain {@code *Test} — no Quarkus boot.
 */
class MavenPluginResolverTest {

    private static final String GROUP = "ai.forvum.test";
    private static final String ARTIFACT = "tiny-plugin";
    private static final String VERSION = "1.0.0";

    @Test
    void resolvesAndStreamsTheJarIntoThePluginsDir(@TempDir Path tmp) throws IOException {
        Path remote = seedRemoteRepository(tmp.resolve("remote"));
        Path localCache = tmp.resolve("local-cache");
        Path plugins = tmp.resolve("plugins");

        PluginInstallResult result = new MavenPluginResolver().install(
                GROUP + ":" + ARTIFACT + ":" + VERSION,
                plugins, localCache, List.of(fileRemote(remote)));

        Path installed = plugins.resolve(ARTIFACT + "-" + VERSION + ".jar");
        assertTrue(Files.exists(installed),
                () -> "the resolved JAR must land in the plugins dir; saw: " + listing(plugins));
        assertEquals(installed, result.installedJar(), "result must point at the installed JAR");
        assertEquals(GROUP + ":" + ARTIFACT + ":" + VERSION, result.coordinates(),
                "result echoes canonical coordinates");
        assertTrue(Files.exists(result.resolvedJar()),
                () -> "result must point at the resolver's fetched JAR in the local cache; saw: "
                        + result.resolvedJar());
        assertEquals("plugin-bytes", Files.readString(installed),
                "the streamed JAR content must match the source artifact byte-for-byte");
    }

    @Test
    void createsThePluginsDirWhenAbsent(@TempDir Path tmp) throws IOException {
        Path remote = seedRemoteRepository(tmp.resolve("remote"));
        Path plugins = tmp.resolve("does/not/exist/yet");

        new MavenPluginResolver().install(
                GROUP + ":" + ARTIFACT + ":" + VERSION,
                plugins, tmp.resolve("local-cache"), List.of(fileRemote(remote)));

        assertTrue(Files.isDirectory(plugins), "install must create the plugins dir if it is absent");
    }

    @Test
    void unresolvableCoordinateRaisesPluginResolutionException(@TempDir Path tmp) throws IOException {
        Path remote = seedRemoteRepository(tmp.resolve("remote"));

        assertThrows(PluginResolutionException.class, () -> new MavenPluginResolver().install(
                GROUP + ":no-such-artifact:9.9.9",
                tmp.resolve("plugins"), tmp.resolve("local-cache"), List.of(fileRemote(remote))));
    }

    @Test
    void malformedCoordinateRaisesPluginResolutionException(@TempDir Path tmp) {
        assertThrows(PluginResolutionException.class, () -> new MavenPluginResolver().install(
                "not-a-coordinate",
                tmp.resolve("plugins"), tmp.resolve("local-cache"), List.of()));
    }

    /** A {@code file://} {@link RemoteRepository} over the seeded layout. */
    private static RemoteRepository fileRemote(Path repoRoot) {
        return new RemoteRepository.Builder("test", "default", repoRoot.toUri().toString()).build();
    }

    /**
     * Lay out a minimal Maven repository at {@code root} holding {@code tiny-plugin-1.0.0.jar} (whose bytes
     * are the literal {@code "plugin-bytes"}) and its {@code .pom} — the resolver needs the POM to resolve a
     * coordinate. Returns {@code root}.
     */
    private static Path seedRemoteRepository(Path root) throws IOException {
        Path dir = root.resolve(GROUP.replace('.', '/')).resolve(ARTIFACT).resolve(VERSION);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(ARTIFACT + "-" + VERSION + ".jar"), "plugin-bytes");
        Files.writeString(dir.resolve(ARTIFACT + "-" + VERSION + ".pom"), pom());
        return root;
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

    private static String listing(Path dir) {
        try {
            return Files.exists(dir) ? Files.list(dir).map(Path::toString).toList().toString() : "(absent)";
        } catch (IOException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }
}
