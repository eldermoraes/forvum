package ai.forvum.tools.filesystem;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Produces the single {@link WorkspaceRoot} the filesystem tools are confined to, so
 * {@link FilesystemToolProvider} can {@code @Inject} it. The root is {@code forvum.workspace.root} when
 * set, else {@code $HOME/.forvum/workspace} — resolved lazily and never required to exist at boot
 * ({@link WorkspaceRoot} only normalizes the path; the directory is created on first write), so the
 * native no-{@code ~/.forvum} smoke boots cleanly.
 */
@ApplicationScoped
public class WorkspaceRootProducer {

    @ConfigProperty(name = "forvum.workspace.root")
    Optional<String> configuredRoot;

    @Produces
    @ApplicationScoped
    WorkspaceRoot workspaceRoot() {
        Path root = configuredRoot
                .map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home"), ".forvum", "workspace"));
        return new WorkspaceRoot(root);
    }
}
