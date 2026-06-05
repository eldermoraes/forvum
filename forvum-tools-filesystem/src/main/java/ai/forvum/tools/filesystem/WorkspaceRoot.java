package ai.forvum.tools.filesystem;

import java.nio.file.Path;

/**
 * The directory the filesystem tools are confined to (ULTRAPLAN section 5.3). A workspace-relative path
 * is resolved against the root and {@link Path#normalize() normalized}; if the result is not contained
 * within the root — a {@code ../} traversal or an absolute path — it is refused with
 * {@link WorkspaceEscapeException}, so no tool can read or write outside the workspace.
 *
 * <p>Minimal and self-contained: the full DR-6a output-filter / threat-model contract is deferred (the
 * M9–M12 precedent of shipping without every design-contract issue). {@code startsWith} is path-element
 * based, not string-prefix, so a sibling directory like {@code <root>-evil} is correctly rejected.
 */
public final class WorkspaceRoot {

    private final Path root;

    public WorkspaceRoot(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * Resolve a workspace-relative path to an absolute path inside the root.
     *
     * @throws WorkspaceEscapeException if {@code relativePath} resolves outside the root
     */
    public Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new WorkspaceEscapeException(relativePath, root);
        }
        return resolved;
    }

    /** The confined root directory (absolute, normalized). */
    public Path root() {
        return root;
    }
}
