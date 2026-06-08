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
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Resolves a Maven coordinate ({@code groupId:artifactId:version}) via Apache Maven Resolver and streams
 * the resolved JAR into {@code ~/.forvum/plugins/} — the engine half of P2-6 ({@code forvum plugin install
 * <coords>}). Resolution checks the user's {@code ~/.m2/repository} local cache first (so an artifact the
 * user already has needs no network) and falls back to Maven Central for anything missing.
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
     * ({@code artifactId-version.jar}); a re-install overwrites it.
     *
     * @return the resolution outcome (canonical coordinates + resolved + installed paths)
     * @throws PluginResolutionException if the coordinate is malformed or cannot be resolved
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

        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(requested);
        request.setRepositories(remotes);

        ArtifactResult result;
        try {
            result = system.resolveArtifact(session, request);
        } catch (ArtifactResolutionException e) {
            throw new PluginResolutionException(
                    "Could not resolve plugin coordinate '" + coordinates + "': " + e.getMessage(), e);
        }

        Artifact resolved = result.getArtifact();
        Path resolvedJar = resolved.getFile().toPath();
        Path installedJar = streamInto(resolvedJar, pluginsDir);

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

    /** Stream {@code resolvedJar} into {@code pluginsDir} (created if absent); returns the installed path. */
    private static Path streamInto(Path resolvedJar, Path pluginsDir) {
        try {
            Files.createDirectories(pluginsDir);
            Path target = pluginsDir.resolve(resolvedJar.getFileName().toString());
            // Files.copy streams the bytes; no full in-memory buffer of the JAR.
            Files.copy(resolvedJar, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to write resolved plugin JAR into " + pluginsDir, e);
        }
    }

    private static String coordinatesOf(Artifact a) {
        return a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion();
    }

    /** The user's {@code ~/.m2/repository} — already-cached artifacts resolve without a network round-trip. */
    private static Path localM2Repository() {
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    /** The configured remote (Maven Central by default; a {@code file://} layout under a hermetic test). */
    private RemoteRepository remote() {
        return new RemoteRepository.Builder(CENTRAL_ID, "default", remoteRepositoryUrl).build();
    }
}
