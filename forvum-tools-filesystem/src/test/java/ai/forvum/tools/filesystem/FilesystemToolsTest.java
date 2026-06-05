package ai.forvum.tools.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The M14 Verify (ULTRAPLAN section 7.1): a read/write/list round-trip against a {@code @TempDir}
 * workspace, and a write outside the workspace root is denied. Exercises the tools directly (their
 * execution is not engine-wired until M18); the denial is the tool's own {@link WorkspaceRoot}
 * containment, self-contained in this module.
 */
class FilesystemToolsTest {

    @Test
    void writeThenReadRoundTrip(@TempDir Path dir) throws IOException {
        WorkspaceRoot workspace = new WorkspaceRoot(dir);
        FsWriteTool write = new FsWriteTool(workspace);
        FsReadTool read = new FsReadTool(workspace);

        write.write("notes/today.md", "buy milk");

        assertEquals("buy milk", Files.readString(dir.resolve("notes/today.md")),
                "the file is written under the workspace root");
        assertEquals("buy milk", read.read("notes/today.md"),
                "the read tool returns the written content");
    }

    @Test
    void listReturnsTheDirectoryEntriesSorted(@TempDir Path dir) throws IOException {
        WorkspaceRoot workspace = new WorkspaceRoot(dir);
        FsWriteTool write = new FsWriteTool(workspace);
        FsListTool list = new FsListTool(workspace);

        write.write("beta.txt", "b");
        write.write("alpha.txt", "a");

        assertEquals(List.of("alpha.txt", "beta.txt"), list.list("."));
    }

    @Test
    void writeOutsideTheWorkspaceIsDenied(@TempDir Path dir) {
        WorkspaceRoot workspace = new WorkspaceRoot(dir);
        FsWriteTool write = new FsWriteTool(workspace);

        assertThrows(WorkspaceEscapeException.class, () -> write.write("../escape.txt", "owned"));
        assertFalse(Files.exists(dir.resolveSibling("escape.txt")),
                "a denied write must not create a file outside the workspace");
    }

    @Test
    void readOutsideTheWorkspaceIsDenied(@TempDir Path dir) {
        WorkspaceRoot workspace = new WorkspaceRoot(dir);
        FsReadTool read = new FsReadTool(workspace);

        assertThrows(WorkspaceEscapeException.class, () -> read.read("../../../../etc/passwd"));
    }

    @Test
    void writeOverwritesAnExistingFile(@TempDir Path dir) throws IOException {
        WorkspaceRoot workspace = new WorkspaceRoot(dir);
        FsWriteTool write = new FsWriteTool(workspace);

        write.write("note.txt", "a long first version");
        write.write("note.txt", "x");

        assertEquals("x", Files.readString(dir.resolve("note.txt")),
                "a second write truncates, not appends (no leftover bytes from the longer first write)");
    }

    @Test
    void listOutsideTheWorkspaceIsDenied(@TempDir Path dir) {
        WorkspaceRoot workspace = new WorkspaceRoot(dir);
        FsListTool list = new FsListTool(workspace);

        assertThrows(WorkspaceEscapeException.class, () -> list.list(".."));
    }
}
