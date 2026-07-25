package ai.forvum.tools.multimodal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@link WorkspaceRoot}: traversal, sibling {@code -evil}, and symlink-out are rejected; an in-workspace
 *  file resolves to its real path. Adapted from the filesystem module's confinement test. */
class WorkspaceRootHardeningTest {

    @TempDir
    Path tmp;

    @Test
    void traversalAndAbsolutePathsAreRejected() throws IOException {
        WorkspaceRoot root = new WorkspaceRoot(Files.createDirectories(tmp.resolve("ws")));
        assertThrows(WorkspaceEscapeException.class, () -> root.resolveForRead("../secret"));
        assertThrows(WorkspaceEscapeException.class, () -> root.resolveForRead("/etc/passwd"));
    }

    @Test
    void siblingRootPrefixIsRejected() throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.createDirectories(tmp.resolve("ws-evil"));
        WorkspaceRoot root = new WorkspaceRoot(ws);
        // Element-wise startsWith, not string-prefix: <root>-evil must not be treated as inside <root>.
        assertThrows(WorkspaceEscapeException.class, () -> root.resolveForRead("../ws-evil/x.png"));
    }

    @Test
    void symlinkEscapingTheRootIsRejected() throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.pdf"), "%PDF-1.4 secret");
        Path link = ws.resolve("link.pdf");
        try {
            Files.createSymbolicLink(link, outside.resolve("secret.pdf"));
        } catch (UnsupportedOperationException | IOException e) {
            return; // symlinks unsupported on this FS — skip (the lexical + realPath filter still holds)
        }
        WorkspaceRoot root = new WorkspaceRoot(ws);
        assertThrows(WorkspaceEscapeException.class, () -> root.resolveForRead("link.pdf"));
    }

    @Test
    void aNonExistentInWorkspacePathResolvesLexically() throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        WorkspaceRoot root = new WorkspaceRoot(ws);
        Path resolved = root.resolveForRead("not-there.png"); // no throw: a missing target can't have escaped
        assertTrue(resolved.startsWith(ws.toAbsolutePath().normalize()));
    }

    @Test
    void anInWorkspaceFileResolvesToItsRealPath() throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("a.png"), "x");
        WorkspaceRoot root = new WorkspaceRoot(ws);
        Path resolved = root.resolveForRead("a.png");
        assertTrue(resolved.startsWith(ws.toRealPath()));
        assertEquals("a.png", resolved.getFileName().toString());
    }
}
