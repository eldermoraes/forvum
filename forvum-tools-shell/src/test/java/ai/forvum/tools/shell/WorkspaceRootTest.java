package ai.forvum.tools.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Confinement tests for the shell module's {@link WorkspaceRoot} (its own self-contained copy, since a
 * Layer-3 plugin cannot depend on {@code forvum-tools-filesystem}). A working directory must stay under
 * the root, lexically AND by real path (a symlinked dir escaping the root is rejected).
 */
class WorkspaceRootTest {

    private static void createSymlinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "filesystem does not support symbolic links: " + e.getMessage());
        }
    }

    @Test
    void nullWorkingDirIsTheRoot(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("ws"));
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        assertEquals(root.toRealPath(), workspace.confine(null));
    }

    @Test
    void blankWorkingDirIsTheRoot(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("ws"));
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        assertEquals(root.toRealPath(), workspace.confine("   "));
    }

    @Test
    void resolvesASubdirectoryWithinTheRoot(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("ws"));
        Files.createDirectories(root.resolve("sub"));
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        assertEquals(root.toRealPath().resolve("sub"), workspace.confine("sub"));
    }

    @Test
    void rejectsParentTraversal(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("ws"));
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        assertThrows(WorkspaceEscapeException.class, () -> workspace.confine("../escape"));
    }

    @Test
    void rejectsAnAbsolutePathOutsideTheRoot(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("ws"));
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        assertThrows(WorkspaceEscapeException.class, () -> workspace.confine("/etc"));
    }

    @Test
    void rejectsASiblingDirectoryThatSharesAStringPrefix(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("ws"));
        Files.createDirectories(dir.resolve("ws-evil"));
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        assertThrows(WorkspaceEscapeException.class, () -> workspace.confine("../ws-evil"));
    }

    @Test
    void rejectsASymlinkedWorkingDirThatEscapesTheRoot(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("ws"));
        Path outside = Files.createDirectories(dir.resolve("outside"));
        createSymlinkOrSkip(root.resolve("evil"), outside);
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        assertThrows(WorkspaceEscapeException.class, () -> workspace.confine("evil"),
                "a working dir symlinked out of the root is rejected by the real-path check");
    }

    @Test
    void allowsASymlinkedWorkingDirThatStaysInsideTheRoot(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("ws"));
        Path real = Files.createDirectories(root.resolve("real"));
        createSymlinkOrSkip(root.resolve("alias"), real);
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        assertEquals(real.toRealPath(), workspace.confine("alias"),
                "an intra-workspace symlink is permitted (resolves to its real path under the root)");
    }
}
