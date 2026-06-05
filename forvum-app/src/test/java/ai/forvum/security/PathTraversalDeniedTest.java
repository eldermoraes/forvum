package ai.forvum.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.tools.filesystem.FsWriteTool;
import ai.forvum.tools.filesystem.WorkspaceEscapeException;
import ai.forvum.tools.filesystem.WorkspaceRoot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Security-test layer (ULTRAPLAN section 10): the assembled app's filesystem tool refuses a
 * path-traversal write — it cannot escape its {@link WorkspaceRoot}. Companion to the M13
 * {@code PermissionScopeMismatchTest} (capability-scope denial); this is path-confinement denial. The
 * {@code WorkspaceRoot} rejects the path before any IO, so no file is created outside the workspace.
 */
class PathTraversalDeniedTest {

    @Test
    void aWriteThatTraversesOutOfTheWorkspaceIsRefusedAndCreatesNoFile(@TempDir Path dir) {
        FsWriteTool write = new FsWriteTool(new WorkspaceRoot(dir));
        String escapeName = "forvum-escape-marker.txt";

        assertThrows(WorkspaceEscapeException.class, () -> write.write("../" + escapeName, "owned"));

        assertFalse(Files.exists(dir.getParent().resolve(escapeName)),
                "a denied traversal must create no file outside the workspace root");
    }
}
