package ai.forvum.tools.sandbox;

import java.nio.file.Path;

/**
 * Thrown when a {@code sandbox.run} working directory resolves outside the configured {@link WorkspaceRoot}
 * — a {@code ../} traversal or absolute path (the lexical filter) or a symlink whose real path escapes the
 * root (the §9.2.5 / #27 link-resolving filter). The sandbox tool mounts and confines its effective working
 * directory to the workspace; this is the module's self-contained containment check, distinct from the
 * engine's capability-scope {@code PermissionDeniedException}. A workspace escape is rethrown to the engine,
 * which records the invocation {@code error} (the ratified disposition for #27 — not remapped to
 * {@code denied}).
 */
public final class WorkspaceEscapeException extends RuntimeException {

    public WorkspaceEscapeException(String requestedPath, Path root) {
        super("Working directory '" + requestedPath + "' escapes the workspace root '" + root
            + "'. sandbox.run is confined to the workspace root.");
    }
}
