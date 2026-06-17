package ai.forvum.tools.shell;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * The directory a {@code shell.exec} working directory is confined to (ULTRAPLAN §9.2.5 / #27). A
 * Layer-3 plugin cannot depend on another plugin, so this is the shell module's own self-contained
 * confinement seam (the same hardened two-stage approach as {@code forvum-tools-filesystem}'s
 * {@code WorkspaceRoot}, not promoted to a shared layer per the ratified PR-6 decision).
 *
 * <p>Confinement is two-stage: a lexical first filter ({@code normalize} + element-wise {@code startsWith},
 * rejecting {@code ../}, absolute paths, and sibling {@code <root>-evil} dirs), then a link-resolving
 * authoritative filter ({@link Path#toRealPath(LinkOption...) toRealPath}, rejecting a working directory
 * symlinked out of the root). The working directory MUST exist (a process cannot run in a non-existent
 * directory), so unlike the filesystem write path there is no not-yet-existing case.
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
     * Confine a working directory: when {@code workingDir} is {@code null} or blank the root itself is the
     * working directory (canonicalized); otherwise it is resolved against the root, lexically filtered,
     * then — if it exists — its real path is asserted to stay under the canonical root.
     *
     * @return the absolute, link-resolved working directory to launch the process in
     * @throws WorkspaceEscapeException if the directory escapes the root lexically or by real path
     */
    public Path confine(String workingDir) {
        if (workingDir == null || workingDir.isBlank()) {
            return Files.exists(root, LinkOption.NOFOLLOW_LINKS) ? canonicalRoot() : root;
        }
        Path lexical = root.resolve(workingDir).normalize();
        if (!lexical.startsWith(root)) {
            throw new WorkspaceEscapeException(workingDir, root);
        }
        if (!Files.exists(lexical, LinkOption.NOFOLLOW_LINKS)) {
            // A non-existent working directory cannot have escaped via a link; the process launch will
            // fail later with a clear error.
            return lexical;
        }
        Path real = realPath(lexical);
        if (!real.startsWith(canonicalRoot())) {
            throw new WorkspaceEscapeException(workingDir, canonicalRoot());
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
                    "Cannot canonicalize working directory '" + path + "' for shell confinement.", e);
        }
    }
}
