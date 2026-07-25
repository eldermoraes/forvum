package ai.forvum.tools.multimodal;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Produces the {@link WorkspaceRoot} the multimodal tools read media files from — the same
 * {@code forvum.workspace.root} (else {@code $HOME/.forvum/workspace}) as the filesystem/shell tools, so a
 * single workspace is shared. This is the multimodal module's own type
 * ({@code ai.forvum.tools.multimodal.WorkspaceRoot}), distinct from the same-named classes in the other
 * tool modules, so no injection ambiguity arises when they are all on the app classpath. The root is
 * resolved lazily and never required to exist at boot ({@link WorkspaceRoot} only normalizes the path), so
 * the native no-{@code ~/.forvum} smoke boots cleanly.
 */
@ApplicationScoped
public class WorkspaceRootProducer {

    @ConfigProperty(name = "forvum.workspace.root")
    Optional<String> configuredRoot;

    @Produces
    @ApplicationScoped
    WorkspaceRoot multimodalWorkspaceRoot() {
        Path root = configuredRoot
                .map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home"), ".forvum", "workspace"));
        return new WorkspaceRoot(root);
    }
}
