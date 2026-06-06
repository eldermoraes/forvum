package ai.forvum.tools.filesystem;

import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.AbstractToolProvider;
import ai.forvum.sdk.ForvumExtension;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * First first-party tool module (ULTRAPLAN section 7.1 M14 / 5.5). Contributes the filesystem tools —
 * {@code fs.read}, {@code fs.write}, {@code fs.list} — to the engine's global ToolRegistry, which
 * discovers this {@code @ApplicationScoped} bean via CDI, and (M18 Option A) executes them through
 * {@link #invoke(String, Map)}. The engine's ToolExecutor gates permission + audits every call; this
 * provider only dispatches the permitted call by name to the confined {@code Fs*Tool} logic (no
 * reflection). All paths are confined to the injected {@link WorkspaceRoot}.
 */
@ForvumExtension
@ApplicationScoped
public class FilesystemToolProvider extends AbstractToolProvider {

    @Inject
    WorkspaceRoot workspace;

    @Override
    public String extensionId() {
        return "filesystem";
    }

    @Override
    public List<ToolSpec> tools() {
        return List.of(FsReadTool.SPEC, FsWriteTool.SPEC, FsListTool.SPEC);
    }

    @Override
    public String invoke(String toolName, Map<String, Object> arguments) {
        try {
            return switch (toolName) {
                case "fs.read" -> new FsReadTool(workspace).read(stringArg(arguments, "path"));
                case "fs.write" -> new FsWriteTool(workspace)
                        .write(stringArg(arguments, "path"), stringArg(arguments, "content"));
                case "fs.list" -> String.join("\n", new FsListTool(workspace)
                        .list(stringArg(arguments, "path")));
                default -> throw new IllegalArgumentException(
                        "FilesystemToolProvider does not contribute a tool named '" + toolName
                      + "'. It provides fs.read, fs.write, fs.list.");
            };
        } catch (IOException e) {
            throw new UncheckedIOException("Filesystem tool '" + toolName + "' failed: " + e.getMessage(), e);
        }
    }

    /** The required {@code String} argument {@code key}; the model is contractually obliged to supply it. */
    private static String stringArg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Missing required argument '" + key + "' for a filesystem tool call.");
        }
        return value.toString();
    }
}
