package ai.forvum.tools.filesystem;

import java.nio.file.Path;

/**
 * The directory the filesystem tools are confined to (ULTRAPLAN section 5.3). A workspace-relative path
 * is resolved against the root and {@link Path#normalize() normalized}; if the result is not contained
 * within the root — a {@code ../} traversal or an absolute path — it is refused with
 * {@link WorkspaceEscapeException}. {@code startsWith} is path-element based, not string-prefix, so a
 * sibling directory like {@code <root>-evil} is correctly rejected.
 *
 * <p><strong>Minimal and self-contained — lexical confinement only.</strong> The check does NOT resolve
 * symbolic links: a symlink inside the workspace that points outside it is still followed by the
 * underlying {@code java.nio} I/O, and check-then-use (TOCTOU) is not guarded. Link-resolving confinement
 * ({@code toRealPath} / {@code O_NOFOLLOW}) and TOCTOU hardening belong to the deferred DR-6a WorkspaceRoot
 * contract — out of scope under Forvum's single-user, local-first trust boundary (the operator authors
 * their own workspace), consistent with the M9–M12 ship-without-every-contract precedent.
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
