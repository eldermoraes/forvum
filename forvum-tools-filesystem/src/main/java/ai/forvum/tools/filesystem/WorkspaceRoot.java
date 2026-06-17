package ai.forvum.tools.filesystem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * The directory the filesystem tools are confined to (ULTRAPLAN section 5.3; the §9.2.5 / #27
 * link-resolving confinement obligation). Confinement is two-stage:
 *
 * <ol>
 *   <li><strong>Lexical first filter.</strong> A workspace-relative path is resolved against the root and
 *       {@link Path#normalize() normalized}; if the result is not contained within the root — a {@code ../}
 *       traversal or an absolute path — it is refused with {@link WorkspaceEscapeException}.
 *       {@code startsWith} is path-element based, not string-prefix, so a sibling directory like
 *       {@code <root>-evil} is correctly rejected. This is the cheap, IO-free check (preserved as
 *       {@link #resolve(String)}).</li>
 *   <li><strong>Real-path authoritative filter.</strong> {@link #resolveForRead(String)} and
 *       {@link #resolveForWrite(String)} additionally resolve symbolic links via
 *       {@link Path#toRealPath(LinkOption...) toRealPath} and assert the canonical target stays under the
 *       canonical root. This rejects a symlink inside the workspace whose real target escapes it (the
 *       lexical check alone would pass). For a not-yet-existing write target the deepest existing ancestor
 *       is canonicalized and the non-existent tail re-appended.</li>
 * </ol>
 *
 * <p>Symbolic links that point <em>inside</em> the workspace remain allowed ({@code toRealPath} still
 * lands under the canonical root) — the hardening rejects only escape, it does not ban intra-workspace
 * symlinks.
 *
 * <p>The root may not exist at boot (the no-{@code ~/.forvum} native smoke must still construct this
 * type), so the constructor only normalizes the path; the canonical root is computed lazily, per call,
 * and falls back to the lexical absolute-normalized path until the directory exists. The lazily memoized
 * canonical-root field is {@code volatile} with recompute-if-null (idempotent), so no {@code synchronized}
 * is used (CLAUDE.md §3.8).
 *
 * <p><strong>Residual TOCTOU.</strong> A narrow window between this check and the subsequent I/O can be
 * exploited by swapping the final path element to a symlink. The {@code Fs*Tool} classes close it
 * best-effort by opening the final component with {@link LinkOption#NOFOLLOW_LINKS} (a swapped symlink
 * then fails the open) and re-asserting containment; this is best-effort containment, not a lock.
 */
public final class WorkspaceRoot {

    private final Path root;
    private volatile Path canonicalRoot;

    public WorkspaceRoot(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * Lexically resolve a workspace-relative path to an absolute path inside the root (the cheap IO-free
     * first filter). Does NOT resolve symbolic links — prefer {@link #resolveForRead(String)} /
     * {@link #resolveForWrite(String)} for tool I/O; this stays public for callers needing only the
     * lexical guarantee.
     *
     * @throws WorkspaceEscapeException if {@code relativePath} lexically resolves outside the root
     */
    public Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new WorkspaceEscapeException(relativePath, root);
        }
        return resolved;
    }

    /**
     * Resolve a workspace-relative path for reading/listing an existing target: lexical filter, then, if
     * the target exists, assert its {@link Path#toRealPath(LinkOption...) real path} stays under the
     * canonical root. Returns the canonical (link-resolved) path when the target exists, else the lexical
     * path (a non-existent target cannot have escaped via a link).
     *
     * @throws WorkspaceEscapeException if the lexical or real path escapes the root
     */
    public Path resolveForRead(String relativePath) {
        Path lexical = resolve(relativePath);
        if (!Files.exists(lexical, LinkOption.NOFOLLOW_LINKS)) {
            return lexical;
        }
        Path real = realPath(lexical);
        assertContained(real, relativePath);
        return real;
    }

    /**
     * Resolve a workspace-relative path for writing a target that may not exist yet: lexical filter, then
     * canonicalize the deepest EXISTING ancestor and re-append the non-existent tail, asserting the
     * reconstructed path stays under the canonical root. This rejects a write through a symlinked parent
     * directory that points outside the root.
     *
     * @throws WorkspaceEscapeException if the lexical or reconstructed real path escapes the root
     */
    public Path resolveForWrite(String relativePath) {
        Path lexical = resolve(relativePath);
        Path existing = lexical;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            // No ancestor exists (root itself is absent yet); fall back to the lexical path.
            return lexical;
        }
        Path realExisting = realPath(existing);
        // Re-append the non-existent tail to the canonical existing ancestor.
        Path tail = existing.relativize(lexical);
        Path reconstructed = realExisting.resolve(tail).normalize();
        assertContained(reconstructed, relativePath);
        return reconstructed;
    }

    /** The confined root directory (absolute, normalized). */
    public Path root() {
        return root;
    }

    /**
     * The canonical (real, link-resolved) root, lazily memoized. Falls back to the lexical
     * absolute-normalized {@link #root} until the directory exists, so construction at boot (root absent)
     * never touches the filesystem.
     */
    private Path canonicalRoot() {
        Path cached = canonicalRoot;
        if (cached != null) {
            return cached;
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return root; // not memoized — recompute once the directory materializes
        }
        Path computed = realPath(root);
        canonicalRoot = computed;
        return computed;
    }

    private void assertContained(Path realTarget, String relativePath) {
        if (!realTarget.startsWith(canonicalRoot())) {
            throw new WorkspaceEscapeException(relativePath, canonicalRoot());
        }
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot canonicalize path '" + path + "' for workspace confinement.", e);
        }
    }
}
