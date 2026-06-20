package ai.forvum.tools.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Confinement tests for the sandbox module's self-contained {@link WorkspaceRoot} (a copy of the shell
 * module's, since a Layer-3 plugin cannot depend on a sibling). The two-stage filter rejects {@code ../}
 * traversal, absolute paths, sibling {@code <root>-evil} directories, and symlinks whose real path escapes
 * the root.
 */
class WorkspaceRootTest {

    @Test
    void aNullOrBlankWorkingDirIsTheRoot(@TempDir Path root) throws IOException {
        Files.createDirectories(root);
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        assertEquals(root.toRealPath(), workspace.confine(null));
        assertEquals(root.toRealPath(), workspace.confine("  "));
    }

    @Test
    void aRelativeSubdirIsConfined(@TempDir Path root) throws IOException {
        Path sub = Files.createDirectories(root.resolve("a").resolve("b"));
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        assertEquals(sub.toRealPath(), workspace.confine("a/b"));
    }

    @Test
    void aTraversalEscapeIsRejected(@TempDir Path root) {
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        assertThrows(WorkspaceEscapeException.class, () -> workspace.confine("../escape"));
        assertThrows(WorkspaceEscapeException.class, () -> workspace.confine("a/../../escape"));
    }

    @Test
    void anAbsolutePathIsRejected(@TempDir Path root) {
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        assertThrows(WorkspaceEscapeException.class, () -> workspace.confine("/etc"));
    }

    @Test
    void aSiblingPrefixDirIsRejected(@TempDir Path parent) throws IOException {
        Path root = Files.createDirectories(parent.resolve("ws"));
        Files.createDirectories(parent.resolve("ws-evil"));
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        // "../ws-evil" lexically normalizes outside the root even though it shares the "ws" prefix.
        assertThrows(WorkspaceEscapeException.class, () -> workspace.confine("../ws-evil"));
    }

    @Test
    void aSymlinkEscapingTheRootIsRejected(@TempDir Path parent) throws IOException {
        Path root = Files.createDirectories(parent.resolve("ws"));
        Path outside = Files.createDirectories(parent.resolve("outside"));
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symlinks unsupported on this platform");
        }
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        assertThrows(WorkspaceEscapeException.class, () -> workspace.confine("link"),
                "a working dir symlinked out of the root is rejected by the real-path filter");
    }

    @Test
    void aNonExistentSubdirIsReturnedLexically(@TempDir Path root) {
        WorkspaceRoot workspace = new WorkspaceRoot(root);

        Path confined = workspace.confine("not/created/yet");

        assertTrue(confined.startsWith(root.toAbsolutePath().normalize()),
                "a not-yet-existing dir stays under the root (the mount will fail later with a clear error)");
    }

    @Test
    void rootExposesTheNormalizedAbsolutePath(@TempDir Path root) {
        WorkspaceRoot workspace = new WorkspaceRoot(root.resolve("a").resolve("..").resolve("b"));

        assertEquals(root.resolve("b").toAbsolutePath().normalize(), workspace.root());
    }
}
