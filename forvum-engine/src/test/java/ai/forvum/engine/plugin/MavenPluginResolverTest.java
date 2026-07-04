package ai.forvum.engine.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Drives {@link MavenPluginResolver} against a hermetic {@code file://} remote repository — a tiny JAR +
 * its POM + a {@code .jar.sha1} checksum sidecar laid out in Maven layout in a {@code @TempDir} — so the
 * test resolves and streams without any network or {@code ~/.m2} dependence. Asserts the resolved JAR lands
 * in the {@code plugins/} dir with the resolver's canonical {@code artifactId-version.jar} filename, and
 * that malformed/missing coordinates surface as a {@link PluginResolutionException}. Plain {@code *Test} —
 * no Quarkus boot.
 *
 * <p><strong>#171 strict-checksum hardening.</strong> The resolver enforces
 * {@link RepositoryPolicy#CHECKSUM_POLICY_FAIL} (missing OR mismatched checksum aborts), so the hermetic
 * fixture MUST write a valid {@code .jar.sha1} sidecar — the pre-#171 no-sha1 fixture shape is now the
 * {@code missingChecksumIsRejected} negative test. Every negative test uses a FRESH local-cache
 * {@code @TempDir}: a previously cached artifact resolves with no checksum re-verification (the operator's
 * own disk is trusted), so reusing a warm cache would make a tampered-remote test pass for the wrong reason.
 */
class MavenPluginResolverTest {

    private static final String GROUP = "ai.forvum.test";
    private static final String ARTIFACT = "tiny-plugin";
    private static final String VERSION = "1.0.0";
    private static final String JAR_BYTES = "plugin-bytes";

    @Test
    void resolvesAndStreamsTheJarIntoThePluginsDir(@TempDir Path tmp) throws IOException {
        Path remote = seedRemoteRepositoryWithValidChecksum(tmp.resolve("remote"));
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
        assertEquals(JAR_BYTES, Files.readString(installed),
                "the streamed JAR content must match the source artifact byte-for-byte");
    }

    @Test
    void createsThePluginsDirWhenAbsent(@TempDir Path tmp) throws IOException {
        Path remote = seedRemoteRepositoryWithValidChecksum(tmp.resolve("remote"));
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

    // --- #171: strict-checksum enforcement -----------------------------------------------------------

    @Test
    void tamperedChecksumIsRejectedAndInstallsNothing(@TempDir Path tmp) throws IOException {
        // Seed a remote whose .jar.sha1 does NOT match the JAR bytes — the live-vulnerability case.
        Path remote = seedRemoteRepositoryWithChecksum(tmp.resolve("remote"), "deadbeefdeadbeefdeadbeefdeadbeef");
        Path plugins = tmp.resolve("plugins");

        PluginResolutionException thrown = assertThrows(PluginResolutionException.class,
                () -> new MavenPluginResolver().install(
                        GROUP + ":" + ARTIFACT + ":" + VERSION,
                        plugins, tmp.resolve("local-cache"), List.of(fileRemote(remote))),
                "a checksum mismatch must abort resolution (CHECKSUM_POLICY_FAIL), not warn-and-accept");
        assertTrue(thrown.getMessage().toLowerCase().contains("checksum"),
                () -> "the diagnostic must mention the checksum failure; got: " + thrown.getMessage());
        // Acceptance #1: a checksum mismatch leaves no loadable target JAR (and no partial .tmp residue).
        assertTrue(noArtifactStaged(plugins),
                () -> "a rejected artifact must leave no JAR/.tmp in the plugins dir; saw: " + listing(plugins));
    }

    @Test
    void missingChecksumIsRejectedAndInstallsNothing(@TempDir Path tmp) throws IOException {
        // The pre-#171 fixture shape (JAR + POM, no .sha1) now fails: strict policy refuses missing checksums.
        Path remote = seedRemoteRepository(tmp.resolve("remote"));
        Path plugins = tmp.resolve("plugins");

        assertThrows(PluginResolutionException.class, () -> new MavenPluginResolver().install(
                GROUP + ":" + ARTIFACT + ":" + VERSION,
                plugins, tmp.resolve("local-cache"), List.of(fileRemote(remote))),
                "a missing checksum must abort resolution under CHECKSUM_POLICY_FAIL");
        assertTrue(noArtifactStaged(plugins),
                () -> "a rejected artifact must leave no JAR/.tmp in the plugins dir; saw: " + listing(plugins));
    }

    @Test
    void remoteRepositoryPolicyIsChecksumFailForReleasesAndSnapshots() {
        MavenPluginResolver resolver = new MavenPluginResolver();
        resolver.remoteRepositoryUrl = "https://repo.maven.apache.org/maven2/";

        RemoteRepository remote = resolver.remote();

        RepositoryPolicy release = remote.getPolicy(false);
        RepositoryPolicy snapshot = remote.getPolicy(true);
        assertEquals(RepositoryPolicy.CHECKSUM_POLICY_FAIL, release.getChecksumPolicy(),
                "the release policy must be explicit CHECKSUM_POLICY_FAIL, independent of Resolver defaults");
        assertEquals(RepositoryPolicy.CHECKSUM_POLICY_FAIL, snapshot.getChecksumPolicy(),
                "the snapshot policy must be explicit CHECKSUM_POLICY_FAIL");
        assertTrue(release.isEnabled(), "the release policy must be enabled");
        assertTrue(snapshot.isEnabled(), "the snapshot policy must be enabled");
    }

    // --- #171: repository scheme allowlist -----------------------------------------------------------

    @Test
    void httpRepositoryUrlIsRejected() {
        MavenPluginResolver resolver = new MavenPluginResolver();
        resolver.remoteRepositoryUrl = "http://insecure.example.com/maven2/";

        PluginResolutionException thrown = assertThrows(PluginResolutionException.class, resolver::remote,
                "plaintext http:// is a MITM downgrade and must be rejected");
        assertTrue(thrown.getMessage().contains("https"),
                () -> "the rejection must name the accepted https scheme; got: " + thrown.getMessage());
    }

    @Test
    void httpsAndFileRepositoryUrlsAreAccepted() {
        MavenPluginResolver https = new MavenPluginResolver();
        https.remoteRepositoryUrl = "https://repo.maven.apache.org/maven2/";
        assertEquals("https", https.remote().getProtocol(), "https:// must build a remote repository");

        MavenPluginResolver file = new MavenPluginResolver();
        file.remoteRepositoryUrl = "file:///tmp/local-mirror/";
        // file:// is the sanctioned local-mirror / hermetic-test path (checksum FAIL still applies to it).
        assertEquals("file", file.remote().getProtocol(), "file:// must build a remote repository");
    }

    // --- #171: credential-free diagnostics ------------------------------------------------------------

    @Test
    void resolutionFailureDiagnosticOmitsUrlCredentials(@TempDir Path tmp) {
        // An unresolvable artifact through a userinfo-bearing remote: the failure message must carry the
        // coordinates and host but NOT the secret in the URL userinfo.
        RemoteRepository credentialed = new RemoteRepository.Builder(
                "test", "default", "https://user:hunter2@host.example.com/maven2/").build();

        PluginResolutionException thrown = assertThrows(PluginResolutionException.class,
                () -> new MavenPluginResolver().install(
                        GROUP + ":no-such-artifact:9.9.9",
                        tmp.resolve("plugins"), tmp.resolve("local-cache"), List.of(credentialed)),
                "an unresolvable coordinate must raise PluginResolutionException");
        assertTrue(thrown.getMessage().contains("no-such-artifact"),
                () -> "the diagnostic must name the coordinate; got: " + thrown.getMessage());
        assertFalse(thrown.getMessage().contains("hunter2"),
                () -> "the diagnostic must NOT leak the URL credential; got: " + thrown.getMessage());
    }

    // --- fixtures ------------------------------------------------------------------------------------

    /** A {@code file://} {@link RemoteRepository} over the seeded layout (default checksum policy). */
    private static RemoteRepository fileRemote(Path repoRoot) {
        return new RemoteRepository.Builder("test", "default", repoRoot.toUri().toString()).build();
    }

    /**
     * Lay out a minimal Maven repository at {@code root} holding {@code tiny-plugin-1.0.0.jar} (bytes
     * {@code "plugin-bytes"}) + its {@code .pom} — but NO checksum sidecar. Used by the missing-checksum
     * negative test and by paths that fail before the JAR-download's checksum check (unresolvable coordinate).
     */
    private static Path seedRemoteRepository(Path root) throws IOException {
        Path dir = artifactDir(root);
        Files.writeString(dir.resolve(ARTIFACT + "-" + VERSION + ".jar"), JAR_BYTES);
        Files.writeString(dir.resolve(ARTIFACT + "-" + VERSION + ".pom"), pom());
        return root;
    }

    /**
     * Lay out {@code root} with the JAR + POM AND a {@code .jar.sha1} sidecar whose content is
     * {@code checksumHex} — a valid SHA-1 makes resolution succeed under CHECKSUM_POLICY_FAIL; a wrong hex
     * makes it abort. The POM needs no checksum (a concrete-version {@code resolveArtifact} does not fetch it).
     */
    private static Path seedRemoteRepositoryWithChecksum(Path root, String checksumHex) throws IOException {
        Path dir = artifactDir(root);
        String jarName = ARTIFACT + "-" + VERSION + ".jar";
        Files.writeString(dir.resolve(jarName), JAR_BYTES);
        Files.writeString(dir.resolve(jarName + ".sha1"), checksumHex);
        Files.writeString(dir.resolve(ARTIFACT + "-" + VERSION + ".pom"), pom());
        return root;
    }

    /** Seed with a VALID {@code .jar.sha1} so resolution passes strict checksums. */
    private static Path seedRemoteRepositoryWithValidChecksum(Path root) throws IOException {
        return seedRemoteRepositoryWithChecksum(root, sha1Hex(JAR_BYTES));
    }

    private static Path artifactDir(Path root) throws IOException {
        Path dir = root.resolve(GROUP.replace('.', '/')).resolve(ARTIFACT).resolve(VERSION);
        Files.createDirectories(dir);
        return dir;
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

    /** True if no {@code .jar} and no {@code .tmp} residue was left staged in {@code plugins}. */
    private static boolean noArtifactStaged(Path plugins) {
        if (!Files.exists(plugins)) {
            return true;
        }
        try (Stream<Path> entries = Files.list(plugins)) {
            return entries.map(p -> p.getFileName().toString())
                    .noneMatch(n -> n.endsWith(".jar") || n.endsWith(".tmp"));
        } catch (IOException e) {
            return false;
        }
    }

    private static String listing(Path dir) {
        if (!Files.exists(dir)) {
            return "(absent)";
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(Path::toString).toList().toString();
        } catch (IOException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }
}
