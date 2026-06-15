package ai.forvum.tools.mcp;

/**
 * Opens an {@link McpServerConnection} for a server spec — the seam that isolates the langchain4j MCP
 * client from {@link McpBridgeToolProvider}. The production implementation
 * ({@code DefaultMcpClientFactory}) builds a langchain4j {@code DefaultMcpClient} over an
 * {@code HttpMcpTransport}; tests bind a fake so the provider is exercised with no live server.
 */
public interface McpClientFactory {

    /**
     * Connect to the MCP server described by {@code spec} (HTTP/SSE). Throws on a connection failure — the
     * provider catches it per-server (best-effort: a down server is skipped, not fatal).
     */
    McpServerConnection connect(McpServerSpec spec);
}
