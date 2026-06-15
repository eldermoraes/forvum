package ai.forvum.app;

import ai.forvum.core.ToolSpec;
import ai.forvum.tools.mcp.McpBridgeToolProvider;
import ai.forvum.tools.mcp.McpServerConfig;
import ai.forvum.tools.mcp.McpServerSpec;

import jakarta.inject.Inject;

import picocli.CommandLine;

import java.net.URI;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code forvum mcp list} (P2-13): show every registered MCP server (from {@code ~/.forvum/mcp-servers/})
 * and the tools it surfaces as {@code mcp.<id>.*}. Best-effort: a server that is unreachable, disabled, or
 * on an unsupported (stdio) transport shows with no tools. {@code mcp} is a {@code CommandMode} one-shot, so
 * the engine ToolRegistry deliberately does NOT materialize MCP tools at boot (that would force the
 * blocking connect onto every one-shot's cold-start path); this command therefore materializes on demand by
 * calling the bridge directly — the connect cost is paid only when the operator explicitly asks to list.
 */
@CommandLine.Command(
        name = "list",
        description = "List registered MCP servers and the tools they surface.")
public class McpListCommand implements Callable<Integer> {

    @Inject
    McpServerConfig config;

    @Inject
    McpBridgeToolProvider bridge;

    @Override
    public Integer call() {
        List<McpServerSpec> servers = config.readAll();
        if (servers.isEmpty()) {
            System.out.println("No MCP servers registered. Add one with `forvum mcp add <url>`.");
            return 0;
        }
        // Materialize live tools on demand (the boot-time materialization is skipped for one-shots).
        List<String> surfaced = bridge.tools().stream().map(ToolSpec::name).sorted().toList();
        for (McpServerSpec server : servers) {
            String state = !server.enabled() ? "disabled"
                    : server.isHttp() ? "enabled" : "enabled, UNSUPPORTED transport (http/sse only in v0.5)";
            System.out.printf("%s  [%s]  %s  (%s)%n", server.id(), server.transport(),
                    server.url() == null ? "(no url)" : redactUrl(server.url()), state);
            String prefix = "mcp." + server.id() + ".";
            List<String> tools = surfaced.stream().filter(name -> name.startsWith(prefix)).toList();
            if (tools.isEmpty()) {
                System.out.println("    (no tools surfaced — server unreachable, disabled, or "
                        + "unsupported transport)");
            } else {
                tools.forEach(tool -> System.out.println("    " + tool));
            }
        }
        return 0;
    }

    /**
     * The server URL with any secret material masked before printing (never log a secret-bearing URL — the
     * Telegram lesson). Drops {@code userinfo} and replaces a non-empty query string with a sentinel, since
     * an operator could embed a token there even though {@code mcp add} writes auth into headers. A URL that
     * does not parse is masked wholesale rather than echoed raw.
     */
    static String redactUrl(String url) {
        try {
            URI u = new URI(url);
            StringBuilder sb = new StringBuilder();
            if (u.getScheme() != null) {
                sb.append(u.getScheme()).append("://");
            }
            if (u.getHost() != null) {
                sb.append(u.getHost());
                if (u.getPort() != -1) {
                    sb.append(':').append(u.getPort());
                }
            }
            if (u.getRawPath() != null) {
                sb.append(u.getRawPath());
            }
            if (u.getUserInfo() != null || (u.getRawQuery() != null && !u.getRawQuery().isEmpty())) {
                sb.append("?<redacted>");
            }
            return sb.isEmpty() ? "<redacted>" : sb.toString();
        } catch (Exception e) {
            return "<redacted>";
        }
    }
}

