package ai.forvum.tools.mcp;

import java.util.List;

/**
 * A narrow, langchain4j-free seam over one connected MCP server: list its tools and invoke one. The
 * default implementation ({@code DefaultMcpClientFactory}) wraps a langchain4j {@code McpClient}; tests
 * provide a fake, so {@code McpBridgeToolProvider} and its tests never touch a live server or a langchain4j
 * type. {@link AutoCloseable} so the provider can release the underlying client.
 */
public interface McpServerConnection extends AutoCloseable {

    /** One tool advertised by the MCP server (the raw tool name, before the {@code mcp.<server>.} prefix). */
    record McpTool(String name, String description, String parametersJsonSchema) {
    }

    /** The server's advertised tools (best-effort; the caller maps them to {@code mcp.<server>.<tool>}). */
    List<McpTool> listTools();

    /**
     * Invoke {@code toolName} (the RAW tool name) with {@code argumentsJson} (a JSON object string) and
     * return the textual result.
     */
    String executeTool(String toolName, String argumentsJson);

    @Override
    void close();
}
