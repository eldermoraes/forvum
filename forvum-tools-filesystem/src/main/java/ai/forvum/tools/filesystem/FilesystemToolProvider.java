package ai.forvum.tools.filesystem;

import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.AbstractToolProvider;
import ai.forvum.sdk.ForvumExtension;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * First first-party tool module (ULTRAPLAN section 7.1 M14). Contributes the filesystem tools —
 * {@code fs.read}, {@code fs.write}, {@code fs.list} — to the engine's global ToolRegistry, which
 * discovers this {@code @ApplicationScoped} bean via CDI. Contribution-only per the M13 SPI: the tool
 * implementations ({@link FsReadTool} et al.) carry the logic and are invoked through the engine's
 * ToolExecutor once the M18 tool loop wires them; this provider only declares the {@link ToolSpec}s.
 */
@ForvumExtension
@ApplicationScoped
public class FilesystemToolProvider extends AbstractToolProvider {

    @Override
    public String extensionId() {
        return "filesystem";
    }

    @Override
    public List<ToolSpec> tools() {
        return List.of(FsReadTool.SPEC, FsWriteTool.SPEC, FsListTool.SPEC);
    }
}
