package ai.forvum.tools.mcp;

import java.util.Map;

/**
 * One operator-registered MCP server, read from {@code $FORVUM_HOME/mcp-servers/<id>.json}. v0.5 supports
 * the HTTP/SSE transport only ({@code transport} {@code "http"}/{@code "sse"}); {@code "stdio"} is parsed
 * but flagged off (Risk #9 — no subprocess spawn until the native smoke passes). {@code headers} are
 * optional per-request headers (e.g. an {@code Authorization} for the MCP server). The server's tools are
 * surfaced into the engine ToolRegistry as {@code mcp.<id>.<tool>} specs carrying
 * {@code PermissionScope.MCP_REMOTE} (remote tool-specs are UNTRUSTED, DR-6b §9.3).
 *
 * @param id       the server id (the {@code .json} filename stem) — the {@code mcp.<id>.} tool-name prefix
 * @param enabled  whether the server is active (a server is enabled unless {@code "enabled": false})
 * @param transport {@code "http"}/{@code "sse"} (supported) or {@code "stdio"} (parsed, unsupported in v0.5)
 * @param url      the MCP server's SSE endpoint URL (required for the http/sse transport)
 * @param headers  optional custom request headers (never logged)
 */
public record McpServerSpec(String id, boolean enabled, String transport, String url,
                            Map<String, String> headers) {

    /** Whether the transport is the v0.5-supported HTTP/SSE one. */
    public boolean isHttp() {
        return "http".equalsIgnoreCase(transport) || "sse".equalsIgnoreCase(transport);
    }
}
