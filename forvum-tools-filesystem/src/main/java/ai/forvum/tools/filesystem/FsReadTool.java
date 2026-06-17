package ai.forvum.tools.filesystem;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Reads a UTF-8 text file at a workspace-relative path (ULTRAPLAN section 7.1 M14, scope
 * {@link PermissionScope#FS_READ}). Confined to the {@link WorkspaceRoot}.
 */
public final class FsReadTool {

    /** The tool this class implements, contributed to the registry by {@code FilesystemToolProvider}. */
    public static final ToolSpec SPEC = new ToolSpec(
            "fs.read",
            "Read a UTF-8 text file at a workspace-relative path and return its contents.",
            PermissionScope.FS_READ,
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\","
          + "\"description\":\"workspace-relative file path\"}},\"required\":[\"path\"]}");

    private final WorkspaceRoot workspace;

    public FsReadTool(WorkspaceRoot workspace) {
        this.workspace = workspace;
    }

    /**
     * Return the contents of the workspace-relative {@code path}. The path is confined through
     * {@link WorkspaceRoot#resolveForRead(String)}, which resolves symbolic links and rejects a target
     * whose real path escapes the workspace root.
     */
    public String read(String path) throws IOException {
        return Files.readString(workspace.resolveForRead(path));
    }
}
