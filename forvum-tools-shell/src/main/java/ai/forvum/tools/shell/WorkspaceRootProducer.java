package ai.forvum.tools.shell;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Produces the {@link WorkspaceRoot} the shell tool confines its working directory to — the same
 * {@code forvum.workspace.root} (else {@code $HOME/.forvum/workspace}) as the filesystem tools, so a single
 * workspace is shared. This is the shell module's own type ({@code ai.forvum.tools.shell.WorkspaceRoot}),
 * distinct from the filesystem module's same-named class, so no injection ambiguity arises when both are on
 * the app classpath. The root is resolved lazily and never required to exist at boot ({@link WorkspaceRoot}
 * only normalizes the path), so the native no-{@code ~/.forvum} smoke boots cleanly.
 */
@ApplicationScoped
public class WorkspaceRootProducer {

    @ConfigProperty(name = "forvum.workspace.root")
    Optional<String> configuredRoot;

    @Produces
    @ApplicationScoped
    WorkspaceRoot shellWorkspaceRoot() {
        Path root = configuredRoot
                .map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home"), ".forvum", "workspace"));
        return new WorkspaceRoot(root);
    }
}
