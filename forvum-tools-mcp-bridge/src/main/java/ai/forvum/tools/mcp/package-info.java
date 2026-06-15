/**
 * The MCP bridge (P2-13): surfaces tools from operator-registered REMOTE MCP servers into the engine
 * ToolRegistry as {@code mcp.<server>.<tool>} tools.
 *
 * <p><strong>Trust model (DR-6b §9.3).</strong> A remote MCP server's tool-specs are UNTRUSTED — they
 * breach the author-authored tool-spec assumption — so every surfaced {@link ai.forvum.core.ToolSpec}
 * carries {@link ai.forvum.core.PermissionScope#MCP_REMOTE}, the RBAC second gate (beyond belt membership)
 * enforced by the engine's effective-scopes check (P2-11). {@code mcp add} is a trust grant for LISTING
 * only; tool results are untrusted DATA framed inside the turn.
 *
 * <p><strong>Configuration.</strong> {@code $FORVUM_HOME/mcp-servers/<id>.json}:
 * <pre>{@code
 * { "transport": "http", "url": "https://my-mcp-server.example/sse",
 *   "headers": { "Authorization": "Bearer ..." }, "enabled": true }
 * }</pre>
 * Written by {@code forvum mcp add <url>}; listed by {@code forvum mcp list}. Hot-loaded
 * ({@code ConfigWatcher} watches {@code mcp-servers/}) — the engine ToolRegistry RESYNCS on a config
 * change, withdrawing a removed server's {@code mcp.<server>.*} specs.
 *
 * <p><strong>Transport (v0.5).</strong> HTTP/SSE only (the Quarkiverse {@code quarkus-langchain4j-mcp}
 * client). A {@code "stdio"} server is parsed but skipped with a warning — spawning subprocesses is a
 * documented follow-up (Risk #9, flip on only after the native smoke passes on all platforms).
 * {@link ai.forvum.tools.mcp.McpClientFactory} is the seam isolating the langchain4j MCP client so the
 * provider and its tests stay langchain4j-free.
 */
package ai.forvum.tools.mcp;
