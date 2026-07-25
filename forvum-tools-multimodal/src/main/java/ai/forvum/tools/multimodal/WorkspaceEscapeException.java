package ai.forvum.tools.multimodal;

import java.nio.file.Path;

/**
 * Thrown when a multimodal-tool file argument resolves outside the configured {@link WorkspaceRoot} — a
 * {@code ../} traversal or an absolute path (the lexical first filter), or a symbolic link whose real path
 * escapes the root (the §9.2.5 / #27 link-resolving second filter). The multimodal tools read only
 * workspace-relative image/PDF files; this is the module's self-contained containment check (a Layer-3
 * plugin cannot depend on a sibling, so it is a copy of the filesystem module's hardened confinement),
 * distinct from the engine's capability-scope {@code PermissionDeniedException}. A workspace escape is
 * rethrown to the engine, which records the invocation {@code error} (not {@code denied} — the #27 DP-9
 * disposition).
 */
public final class WorkspaceEscapeException extends RuntimeException {

    public WorkspaceEscapeException(String requestedPath, Path root) {
        super("Path '" + requestedPath + "' escapes the workspace root '" + root
            + "'. Multimodal tools are confined to the workspace root.");
    }
}
