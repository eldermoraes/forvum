package ai.forvum.tools.sandbox;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Produces the {@link WorkspaceRoot} the sandbox tool confines its working directory to and bind-mounts
 * into the container — the same {@code forvum.workspace.root} (else {@code $HOME/.forvum/workspace}) as the
 * filesystem/shell tools, so a single workspace is shared. This is the sandbox module's own type
 * ({@code ai.forvum.tools.sandbox.WorkspaceRoot}), distinct from the filesystem/shell modules' same-named
 * classes, so no injection ambiguity arises when several are on the app classpath. The root is resolved
 * lazily and never required to exist at boot ({@link WorkspaceRoot} only normalizes the path), so the
 * native no-{@code ~/.forvum} smoke boots cleanly.
 */
@ApplicationScoped
public class WorkspaceRootProducer {

    @ConfigProperty(name = "forvum.workspace.root")
    Optional<String> configuredRoot;

    @Produces
    @ApplicationScoped
    WorkspaceRoot sandboxWorkspaceRoot() {
        Path root = configuredRoot
                .map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home"), ".forvum", "workspace"));
        return new WorkspaceRoot(root);
    }
}
