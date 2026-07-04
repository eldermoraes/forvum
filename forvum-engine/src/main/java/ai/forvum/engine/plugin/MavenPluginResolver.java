package ai.forvum.engine.plugin;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Resolves a Maven coordinate ({@code groupId:artifactId:version}) via Apache Maven Resolver and streams
 * the resolved JAR into {@code ~/.forvum/plugins/} — the engine half of P2-6 ({@code forvum plugin install
 * <coords>}). Resolution checks the user's {@code ~/.m2/repository} local cache first (so an artifact the
 * user already has needs no network) and falls back to Maven Central for anything missing.
 *
 * <p><strong>Strict checksums (#171, DR-6b [DP-10]).</strong> A JVM drop-in plugin executes in-process with
 * core-equivalent authority, so a corrupted/replaced artifact must never be accepted with a warning. This
 * resolver hardens the Resolver's default {@code warn} policy to {@link RepositoryPolicy#CHECKSUM_POLICY_FAIL}
 * explicitly on BOTH the session ({@code session.setChecksumPolicy}, a global override) and every remote
 * repository (an explicit {@code RepositoryPolicy} on release and snapshot), so the guarantee holds
 * independently of Resolver defaults and of how any remote was constructed. A missing OR mismatched checksum
 * aborts resolution and leaves no loadable JAR. Repositories are restricted to the {@code {https, file}}
 * scheme allowlist — plaintext {@code http://} (a MITM downgrade) is rejected; {@code file://} is retained
 * for local mirrors / the hermetic test path (checksum verification still applies to it).
 *
 * <p><strong>Local-cache trust boundary.</strong> An artifact already in {@code ~/.m2/repository} resolves
 * with NO checksum re-verification — identical to Maven itself: the cache is the operator's own disk, inside
 * the install-act-is-the-trust-decision boundary. Re-hashing it would invent a parallel verifier with no
 * reference checksum to compare against.
 *
 * <p><strong>Owner-only install.</strong> The resolved JAR is staged by {@link PluginArtifactInstaller}:
 * owner-only {@code 0700}/{@code 0600} on POSIX, an atomic temp-file + move (never a partial JAR), and
 * symlink rejection on the directory and target.
 *
 * <p><strong>Fast-jar-only by design (§6.2/§6.3), NOT a native carve-out.</strong> The drop-in
 * {@code ~/.forvum/plugins/} directory is loaded only by the JVM fast-jar via {@code ServiceLoader}; the
 * native binary fixes its plugin set at build time and cannot load a JAR added afterwards, so the CLI
 * tells native users to rebuild instead. Maven Resolver therefore RUNS only in the fast-jar path.
 *
 * <p><strong>Native-classpath containment.</strong> These resolver classes nonetheless sit on the native
 * classpath (this bean ships in {@code forvum-engine}, which {@code forvum-app} — the native image —
 * depends on). To keep them inert in the binary: every resolver type is referenced only inside method
 * bodies (never as a field initialized at construction), the bean does no {@code @Startup}/eager work, and
 * nothing here is registered for reflection. A native build that never calls {@link #install} never
 * initializes the resolver graph.
 */
@ApplicationScoped
public class MavenPluginResolver {

    private static final String CENTRAL_ID = "central";
    private static final String DEFAULT_CENTRAL_URL = "https://repo.maven.apache.org/maven2/";

    /**
     * The remote repository the public {@link #install(String, Path)} resolves against (Maven Central by
     * default). Overridable via {@code forvum.plugins.repository-url} so a hermetic test can point it at a
     * {@code file://} layout instead of the network — production never sets it.
     */
    @ConfigProperty(name = "forvum.plugins.repository-url", defaultValue = DEFAULT_CENTRAL_URL)
    String remoteRepositoryUrl;

    /**
     * Resolve {@code coordinates} (Maven {@code groupId:artifactId:version}) against the user's
     * {@code ~/.m2/repository} cache + Maven Central and stream the resolved JAR into {@code pluginsDir}.
     * Creates {@code pluginsDir} if absent. The installed file keeps the resolver's canonical filename
     * ({@code artifactId-version.jar}); a re-install overwrites it atomically.
     *
     * @return the resolution outcome (canonical coordinates + resolved + installed paths)
     * @throws PluginResolutionException if the coordinate is malformed, the repository is disallowed, or the
     *         artifact cannot be resolved (including a missing/mismatched checksum)
     * @throws PluginInstallException if the resolved JAR cannot be staged owner-only into {@code pluginsDir}
     */
    public PluginInstallResult install(String coordinates, Path pluginsDir) {
        return install(coordinates, pluginsDir, localM2Repository(), List.of(remote()));
    }

    /**
     * Resolution core, package-private so a test can inject a hermetic {@code file://} remote and an
     * isolated local cache instead of hitting Central. Streams the resolved JAR into {@code pluginsDir}.
     */
    PluginInstallResult install(String coordinates, Path pluginsDir, Path localRepo,
            List<RemoteRepository> remotes) {
        Artifact requested = parse(coordinates);

        RepositorySystem system = new RepositorySystemSupplier().get();
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        session.setLocalRepositoryManager(
                system.newLocalRepositoryManager(session, new LocalRepository(localRepo.toFile())));
        // Strict checksums (#171): fail — never warn — on a missing/mismatched checksum. Set on the session as
        // a global override so it holds even through a remote built with a default (warn) policy.
        session.setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_FAIL);

        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(requested);
        request.setRepositories(remotes);

        ArtifactResult result;
        try {
            result = system.resolveArtifact(session, request);
        } catch (ArtifactResolutionException e) {
            throw new PluginResolutionException(
                    "Could not resolve plugin coordinate '" + coordinates + "': " + redact(e.getMessage()), e);
        }

        Artifact resolved = result.getArtifact();
        Path resolvedJar = resolved.getFile().toPath();
        Path installedJar = PluginArtifactInstaller.install(resolvedJar, pluginsDir);

        return new PluginInstallResult(coordinatesOf(resolved), resolvedJar, installedJar);
    }

    /**
     * Parse {@code groupId:artifactId:version} into a JAR artifact. {@link DefaultArtifact} also accepts
     * extension/classifier forms; a bare three-part coordinate maps to the default {@code jar} extension,
     * which is exactly a plugin drop-in JAR.
     */
    private static Artifact parse(String coordinates) {
        if (coordinates == null || coordinates.isBlank()) {
            throw new PluginResolutionException("Plugin coordinate must be non-blank "
                    + "(expected groupId:artifactId:version).", null);
        }
        try {
            return new DefaultArtifact(coordinates.trim());
        } catch (IllegalArgumentException e) {
            throw new PluginResolutionException("Malformed plugin coordinate '" + coordinates
                    + "' (expected groupId:artifactId:version).", e);
        }
    }

    private static String coordinatesOf(Artifact a) {
        return a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion();
    }

    /** The user's {@code ~/.m2/repository} — already-cached artifacts resolve without a network round-trip. */
    private static Path localM2Repository() {
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    /**
     * The configured remote (Maven Central by default; a {@code file://} layout under a hermetic test),
     * package-private so a test can assert the checksum policy and scheme allowlist. Strict checksums are set
     * explicitly on both the release and snapshot policy; the URL scheme is restricted to {@code {https, file}}
     * — plaintext {@code http://} is rejected as a MITM downgrade.
     */
    RemoteRepository remote() {
        requireAllowedScheme(remoteRepositoryUrl);
        RepositoryPolicy strict = new RepositoryPolicy(
                true, RepositoryPolicy.UPDATE_POLICY_DAILY, RepositoryPolicy.CHECKSUM_POLICY_FAIL);
        return new RemoteRepository.Builder(CENTRAL_ID, "default", remoteRepositoryUrl)
                .setReleasePolicy(strict)
                .setSnapshotPolicy(strict)
                .build();
    }

    /** Allow only {@code https} (network) and {@code file} (local mirror / hermetic test) repository URLs. */
    private static void requireAllowedScheme(String url) {
        String scheme;
        try {
            scheme = URI.create(url).getScheme();
        } catch (IllegalArgumentException e) {
            throw new PluginResolutionException(
                    "Malformed plugin repository URL '" + redact(url) + "': " + e.getMessage(), e);
        }
        scheme = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("file")) {
            throw new PluginResolutionException("Plugin repository URL '" + redact(url) + "' uses an "
                    + "unsupported scheme '" + scheme + "'; only https (and file:// for a local mirror) is "
                    + "allowed — plaintext http is refused.", null);
        }
    }

    /** Strip {@code ://user:secret@} credentials from a URL so a diagnostic never leaks a repository token. */
    private static String redact(String message) {
        if (message == null) {
            return null;
        }
        return message.replaceAll("://[^/@\\s]*@", "://***@");
    }
}
