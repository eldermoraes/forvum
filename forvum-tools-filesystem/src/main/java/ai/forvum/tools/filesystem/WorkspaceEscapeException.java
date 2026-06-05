package ai.forvum.tools.filesystem;

import java.nio.file.Path;

/**
 * Thrown when a filesystem-tool argument resolves outside the configured {@link WorkspaceRoot} — a
 * {@code ../} traversal or an absolute path. The filesystem tools are confined to the workspace; this is
 * the M14 self-contained containment check, distinct from the engine's capability-scope
 * {@code PermissionDeniedException} (the full DR-6a output-filter / threat-model contract is deferred).
 */
public final class WorkspaceEscapeException extends RuntimeException {

    public WorkspaceEscapeException(String requestedPath, Path root) {
        super("Path '" + requestedPath + "' escapes the workspace root '" + root
            + "'. Filesystem tools are confined to the workspace root.");
    }
}
