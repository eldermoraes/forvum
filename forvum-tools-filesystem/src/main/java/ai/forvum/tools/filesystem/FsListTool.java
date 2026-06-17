package ai.forvum.tools.filesystem;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Lists the entry names of a workspace-relative directory (ULTRAPLAN section 7.1 M14, scope
 * {@link PermissionScope#FS_READ} — there is no separate {@code FS_LIST} scope). Confined to the
 * {@link WorkspaceRoot}.
 */
public final class FsListTool {

    /** The tool this class implements, contributed to the registry by {@code FilesystemToolProvider}. */
    public static final ToolSpec SPEC = new ToolSpec(
            "fs.list",
            "List the entry names of a workspace-relative directory, sorted.",
            PermissionScope.FS_READ,
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\","
          + "\"description\":\"workspace-relative directory path\"}},\"required\":[\"path\"]}");

    private final WorkspaceRoot workspace;

    public FsListTool(WorkspaceRoot workspace) {
        this.workspace = workspace;
    }

    /**
     * The sorted entry names of the workspace-relative directory {@code path}. The path is confined
     * through {@link WorkspaceRoot#resolveForRead(String)}, which resolves symbolic links and rejects a
     * target whose real path escapes the workspace root.
     */
    public List<String> list(String path) throws IOException {
        try (Stream<Path> entries = Files.list(workspace.resolveForRead(path))) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }
}
