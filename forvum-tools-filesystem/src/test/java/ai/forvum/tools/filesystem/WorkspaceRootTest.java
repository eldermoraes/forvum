package ai.forvum.tools.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Unit tests for {@link WorkspaceRoot} path confinement (ULTRAPLAN section 5.3 security; the M14
 * self-contained workspace guard, pending the full DR-6a contract). A workspace-relative path resolves
 * to a child of the root; any path that escapes the root — {@code ../} traversal or an absolute path —
 * is refused with {@link WorkspaceEscapeException}. Pure path math (no filesystem IO).
 */
class WorkspaceRootTest {

    @Test
    void resolvesAPathWithinTheRoot(@TempDir Path dir) {
        WorkspaceRoot workspace = new WorkspaceRoot(dir);

        assertEquals(dir.resolve("notes.txt"), workspace.resolve("notes.txt"));
    }

    @Test
    void resolvesANestedPathWithinTheRoot(@TempDir Path dir) {
        WorkspaceRoot workspace = new WorkspaceRoot(dir);

        assertEquals(dir.resolve("sub/deep/file.txt").normalize(),
                workspace.resolve("sub/deep/file.txt"));
    }

    @Test
    void rejectsParentTraversal(@TempDir Path dir) {
        WorkspaceRoot workspace = new WorkspaceRoot(dir);

        assertThrows(WorkspaceEscapeException.class, () -> workspace.resolve("../escape.txt"));
    }

    @Test
    void rejectsNestedTraversalThatClimbsOutOfTheRoot(@TempDir Path dir) {
        WorkspaceRoot workspace = new WorkspaceRoot(dir);

        assertThrows(WorkspaceEscapeException.class, () -> workspace.resolve("sub/../../escape.txt"));
    }

    @Test
    void rejectsAnAbsolutePathOutsideTheRoot(@TempDir Path dir) {
        WorkspaceRoot workspace = new WorkspaceRoot(dir);

        assertThrows(WorkspaceEscapeException.class, () -> workspace.resolve("/etc/passwd"));
    }

    @Test
    void rejectsASiblingDirectoryThatSharesAStringPrefix(@TempDir Path dir) {
        WorkspaceRoot workspace = new WorkspaceRoot(dir);
        // "<root>-evil" string-starts-with "<root>" but is a different path element — element-wise
        // startsWith must reject it (a String.startsWith regression would let it through).
        String sibling = "../" + dir.getFileName() + "-evil/loot.txt";

        assertThrows(WorkspaceEscapeException.class, () -> workspace.resolve(sibling));
    }
}
