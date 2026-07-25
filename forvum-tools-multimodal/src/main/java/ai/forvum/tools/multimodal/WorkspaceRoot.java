package ai.forvum.tools.multimodal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * The directory the multimodal tools ({@code image.analyze} / {@code pdf.analyze}) read media files from
 * (ULTRAPLAN §5.3; the §9.2.5 / #27 link-resolving confinement obligation). A Layer-3 plugin cannot depend
 * on a sibling, so this is the multimodal module's own self-contained confinement seam — a copy of the
 * filesystem module's hardened two-stage read confinement, minus the write path (these tools never write).
 *
 * <p>Confinement is two-stage:
 * <ol>
 *   <li><strong>Lexical first filter.</strong> A workspace-relative path is resolved against the root and
 *       {@link Path#normalize() normalized}; if the result is not contained within the root — a {@code ../}
 *       traversal or an absolute path — it is refused with {@link WorkspaceEscapeException}.
 *       {@code startsWith} is path-element based, not string-prefix, so a sibling directory like
 *       {@code <root>-evil} is correctly rejected.</li>
 *   <li><strong>Real-path authoritative filter.</strong> {@link #resolveForRead(String)} additionally
 *       resolves symbolic links via {@link Path#toRealPath(LinkOption...) toRealPath} and asserts the
 *       canonical target stays under the canonical root, rejecting a symlink inside the workspace whose
 *       real target escapes it (the lexical check alone would pass).</li>
 * </ol>
 *
 * <p>The root may not exist at boot (the no-{@code ~/.forvum} native smoke must still construct this type),
 * so the constructor only normalizes the path; the canonical root is computed lazily, per call, and falls
 * back to the lexical absolute-normalized path until the directory exists. The lazily memoized
 * canonical-root field is {@code volatile} with recompute-if-null (idempotent) — no {@code synchronized}
 * (CLAUDE.md §3.8).
 */
public final class WorkspaceRoot {

    private final Path root;
    private volatile Path canonicalRoot;

    public WorkspaceRoot(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** The confined root directory (absolute, normalized). */
    public Path root() {
        return root;
    }

    /**
     * Lexically resolve a workspace-relative path to an absolute path inside the root (the cheap IO-free
     * first filter). Does NOT resolve symbolic links — prefer {@link #resolveForRead(String)} for tool I/O.
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
     * Resolve a workspace-relative path for reading an existing media file: lexical filter, then, if the
     * target exists, assert its {@link Path#toRealPath(LinkOption...) real path} stays under the canonical
     * root. Returns the canonical (link-resolved) path when the target exists, else the lexical path (a
     * non-existent target cannot have escaped via a link; the subsequent read then fails with a clear error).
     *
     * @throws WorkspaceEscapeException if the lexical or real path escapes the root
     */
    public Path resolveForRead(String relativePath) {
        Path lexical = resolve(relativePath);
        if (!Files.exists(lexical, LinkOption.NOFOLLOW_LINKS)) {
            return lexical;
        }
        Path real = realPath(lexical);
        if (!real.startsWith(canonicalRoot())) {
            throw new WorkspaceEscapeException(relativePath, canonicalRoot());
        }
        return real;
    }

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

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot canonicalize path '" + path + "' for workspace confinement.", e);
        }
    }
}
