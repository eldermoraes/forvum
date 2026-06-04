package ai.forvum.engine.config;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Reads raw MCP server definitions from {@code $FORVUM_HOME/mcp-servers/<name>.json}. */
@Singleton
public class McpServerReader extends JsonDirectoryReader {

    @Inject
    public McpServerReader(ConfigLoader loader, ForvumHome home) {
        super(loader, home.mcpServers());
    }
}
