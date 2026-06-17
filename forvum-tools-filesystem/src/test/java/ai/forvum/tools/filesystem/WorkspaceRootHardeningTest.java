package ai.forvum.tools.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Hardening tests for {@link WorkspaceRoot} (the §9.2.5 / #27 obligation): beyond the lexical confinement
 * exercised by {@code WorkspaceRootTest}, the resolver also resolves symbolic links and rejects a target
 * whose real path escapes the root. {@code resolveForRead} canonicalizes an existing target;
 * {@code resolveForWrite} canonicalizes the deepest existing ancestor of a not-yet-existing target. A
 * symlink that stays inside the workspace is still permitted. These tests use real filesystem symlinks, so
 * they are skipped on a filesystem that cannot create them.
 */
class WorkspaceRootHardeningTest {

    private static Path createSymlinkOrSkip(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "filesystem does not support symbolic links: " + e.getMessage());
            return null; // unreachable
        }
    }

    @Test
    void resolveForReadRejectsASymlinkWhoseTargetEscapesTheRoot(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "top secret");
        // A symlink INSIDE the workspace pointing at a file OUTSIDE it: lexical confinement passes
        // (the link path is under root) but the real path escapes.
        createSymlinkOrSkip(root.resolve("leak.txt"), outside.resolve("secret.txt"));

        WorkspaceRoot workspace = new WorkspaceRoot(root);

        assertThrows(WorkspaceEscapeException.class, () -> workspace.resolveForRead("leak.txt"),
                "a symlink whose real target escapes the root must be rejected");
    }

    @Test
    void resolveForReadAllowsASymlinkThatStaysInsideTheRoot(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Path realFile = root.resolve("real.txt");
        Files.writeString(realFile, "inside");
        createSymlinkOrSkip(root.resolve("alias.txt"), realFile);

        WorkspaceRoot workspace = new WorkspaceRoot(root);

        // No exception: the real path is still under the canonical root.
        Path resolved = workspace.resolveForRead("alias.txt");
        assertEquals("inside", Files.readString(resolved),
                "an intra-workspace symlink is permitted (hardening rejects only escape)");
    }

    @Test
    void resolveForWriteRejectsAWriteThroughASymlinkedParentThatEscapes(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        // A symlinked directory inside the workspace pointing outside it: a write to "evil/loot.txt"
        // would lexically resolve under root but the real parent escapes.
        createSymlinkOrSkip(root.resolve("evil"), outside);

        WorkspaceRoot workspace = new WorkspaceRoot(root);

        assertThrows(WorkspaceEscapeException.class, () -> workspace.resolveForWrite("evil/loot.txt"),
                "a write whose real parent escapes the root (via a symlinked dir) must be rejected");
    }

    @Test
    void resolveForWriteAllowsANewFileUnderAnExistingRealParent(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("ws"));

        WorkspaceRoot workspace = new WorkspaceRoot(root);

        Path resolved = workspace.resolveForWrite("notes/today.md");
        assertEquals(root.toRealPath().resolve("notes").resolve("today.md"), resolved,
                "a brand-new nested file resolves under the canonical root");
    }

    @Test
    void resolveForReadStillRejectsLexicalTraversalBeforeTouchingTheFilesystem(@TempDir Path tmp)
            throws IOException {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        assertThrows(WorkspaceEscapeException.class, () -> workspace.resolveForRead("../escape.txt"));
    }

    @Test
    void resolveForWriteStillRejectsLexicalTraversalBeforeTouchingTheFilesystem(@TempDir Path tmp)
            throws IOException {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        assertThrows(WorkspaceEscapeException.class, () -> workspace.resolveForWrite("../escape.txt"));
    }
}
