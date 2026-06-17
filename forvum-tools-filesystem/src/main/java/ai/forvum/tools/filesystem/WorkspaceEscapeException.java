package ai.forvum.tools.filesystem;

import java.nio.file.Path;

/**
 * Thrown when a filesystem-tool argument resolves outside the configured {@link WorkspaceRoot} — a
 * {@code ../} traversal or an absolute path (the lexical first filter), or a symbolic link whose real
 * path escapes the root (the §9.2.5 / #27 link-resolving second filter). The filesystem tools are confined
 * to the workspace; this is the module's self-contained containment check, distinct from the engine's
 * capability-scope {@code PermissionDeniedException}. The residual check-then-use window is closed
 * best-effort by opening the final component with {@code NOFOLLOW_LINKS}; see {@link WorkspaceRoot}.
 */
public final class WorkspaceEscapeException extends RuntimeException {

    public WorkspaceEscapeException(String requestedPath, Path root) {
        super("Path '" + requestedPath + "' escapes the workspace root '" + root
            + "'. Filesystem tools are confined to the workspace root.");
    }
}
