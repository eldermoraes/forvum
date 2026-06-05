package ai.forvum.tools.filesystem;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    /** Write {@code content} to the workspace-relative {@code path}; returns a short confirmation. */
    public String write(String path, String content) throws IOException {
        Path target = workspace.resolve(path);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, content);
        return "Wrote " + content.length() + " characters to " + path;
    }
}
