package ai.forvum.tools.filesystem;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes UTF-8 text to a file at a workspace-relative path, creating parent directories and overwriting
 * an existing file (ULTRAPLAN section 7.1 M14, scope {@link PermissionScope#FS_WRITE}). Confined to the
 * {@link WorkspaceRoot}.
 */
public final class FsWriteTool {

    /** The tool this class implements, contributed to the registry by {@code FilesystemToolProvider}. */
    public static final ToolSpec SPEC = new ToolSpec(
            "fs.write",
            "Write UTF-8 text to a file at a workspace-relative path, creating parent directories. "
          + "Overwrites an existing file.",
            PermissionScope.FS_WRITE,
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},"
          + "\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}");

    private final WorkspaceRoot workspace;

    public FsWriteTool(WorkspaceRoot workspace) {
        this.workspace = workspace;
    }

    /**
     * Write {@code content} to the workspace-relative {@code path}; returns a short confirmation. The path
     * is confined through {@link WorkspaceRoot#resolveForWrite(String)} (lexical + real-path containment,
     * so a write through a symlinked parent that escapes the root is rejected). The parent chain is
     * materialized, then re-canonicalized and re-asserted (the create step can itself follow a pre-planted
     * symlink), and the final component is opened with {@link LinkOption#NOFOLLOW_LINKS} so a symlink
     * swapped in between check and open is not followed (it fails the open — audited as an error).
     */
    public String write(String path, String content) throws IOException {
        Path target = workspace.resolveForWrite(path);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
            // Re-canonicalize after creation: the create may have traversed a pre-planted symlink.
            Path realParent = parent.toRealPath();
            if (!realParent.startsWith(workspace.root().toRealPath())) {
                throw new WorkspaceEscapeException(path, workspace.root());
            }
            target = realParent.resolve(target.getFileName());
        }
        // Open the final component without following a symlink (TOCTOU best-effort): CREATE + TRUNCATE +
        // WRITE + NOFOLLOW_LINKS.
        try (FileChannel channel = FileChannel.open(target,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            channel.write(StandardCharsets.UTF_8.encode(content));
        }
        return "Wrote " + content.length() + " characters to " + path;
    }
}
