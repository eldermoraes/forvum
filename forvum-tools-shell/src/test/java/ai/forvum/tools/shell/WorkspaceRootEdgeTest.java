package ai.forvum.tools.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Additional branch coverage of {@link WorkspaceRoot} beyond {@link WorkspaceRootTest}: the
 * not-yet-existing-root cases (the lazy canonical-root recompute-if-null path) and a non-existent
 * subdirectory (returned lexically without a real-path resolution, since the launch fails later with a
 * clear error).
 */
class WorkspaceRootEdgeTest {

    @Test
    void confineNullOnANonExistentRootReturnsTheLexicalRoot(@TempDir Path dir) {
        // The root directory does not exist: confine(null) returns the (lexical) normalized root, NOT a
        // canonicalized path — the Files.exists(root)==false branch.
        Path root = dir.resolve("missing-root");
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        assertEquals(root.toAbsolutePath().normalize(), workspace.confine(null),
                "with a not-yet-existing root, confine(null) is the lexical root (canonicalized lazily later)");
    }

    @Test
    void confineANonExistentSubdirectoryReturnsItLexically(@TempDir Path dir) throws IOException {
        // The root exists but the requested subdir does not: it is returned lexically (no real-path check),
        // and the process launch is expected to fail later with a clear error — the
        // !Files.exists(lexical) branch.
        Path root = Files.createDirectories(dir.resolve("ws"));
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        Path confined = workspace.confine("does/not/exist");

        assertEquals(root.resolve("does/not/exist").toAbsolutePath().normalize(), confined,
                "a non-existent in-root subdir is returned lexically (it cannot have escaped via a link)");
    }

    @Test
    void canonicalRootIsMemoizedAcrossCalls(@TempDir Path dir) throws IOException {
        // Two confine() calls on an existing root: the second hits the cached (canonicalRoot != null)
        // branch rather than recomputing.
        Path root = Files.createDirectories(dir.resolve("ws"));
        Files.createDirectories(root.resolve("a"));
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        Path first = workspace.confine(null);   // computes + memoizes the canonical root
        Path second = workspace.confine("a");    // reuses the memoized canonical root

        assertEquals(root.toRealPath(), first);
        assertEquals(root.toRealPath().resolve("a"), second,
                "the canonical root is memoized and reused on subsequent calls");
    }
}
