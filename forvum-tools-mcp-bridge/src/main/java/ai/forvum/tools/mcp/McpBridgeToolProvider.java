package ai.forvum.tools.mcp;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.AbstractToolProvider;
import ai.forvum.sdk.ForvumExtension;
import ai.forvum.tools.mcp.McpServerConnection.McpTool;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.runtime.ShutdownEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Surfaces the tools of operator-registered remote MCP servers into the engine ToolRegistry (P2-13). Each
 * server's tools become {@code mcp.<server>.<tool>} {@link ToolSpec}s carrying
 * {@link PermissionScope#MCP_REMOTE} — remote tool-specs are UNTRUSTED (DR-6b §9.3), so they sit behind
 * belt membership AND the MCP_REMOTE RBAC gate (the P2-11 effective-scopes check), and their results are
 * untrusted DATA. {@code invoke()} routes to the server's MCP client; the engine's {@code ToolExecutor}
 * has already gated the call.
 *
 * <p><strong>Self-reading + resync.</strong> Like a channel, this Layer-3 module does not depend on
 * forvum-engine: it reads {@code mcp-servers/*.json} via {@link McpServerConfig} on each {@link #tools()}
 * call, so the engine's ToolRegistry resync (which re-calls {@code tools()} on a {@code
 * ConfigurationChangedEvent}) re-materializes the set and withdraws a removed server's specs. Connections
 * are cached per server id and reused by {@code invoke()}; a server dropped from the config has its
 * connection closed. Connecting is best-effort — an unreachable/disabled server is skipped (its tools
 * simply do not appear), never fatal.
 *
 * <p><strong>v0.5 scope.</strong> HTTP/SSE transport only; a {@code "stdio"} server is parsed but skipped
 * with a warning (Risk #9 — no subprocess spawn until the native smoke passes). With no {@code ~/.forvum/}
 * the registry is empty and the provider contributes nothing (the no-config native smoke contract).
 */
@ForvumExtension
@ApplicationScoped
public class McpBridgeToolProvider extends AbstractToolProvider {

    private static final Logger LOG = Logger.getLogger(McpBridgeToolProvider.class);

    /** Extension id; matches {@code META-INF/forvum/plugin.json} and the {@code mcp.} tool-name prefix. */
    static final String EXTENSION_ID = "mcp";
    static final String TOOL_PREFIX = "mcp.";

    private final ObjectMapper mapper = new ObjectMapper();
    /** Live connections keyed by server id; reused across tools()/invoke(), closed on withdrawal/shutdown. */
    private final ConcurrentMap<String, McpServerConnection> connections = new ConcurrentHashMap<>();

    @Inject
    McpServerConfig config;

    @Inject
    McpClientFactory clientFactory;

    @Override
    public String extensionId() {
        return EXTENSION_ID;
    }

    @Override
    public List<ToolSpec> tools() {
        List<ToolSpec> specs = new ArrayList<>();
        Set<String> live = new HashSet<>();
        for (McpServerSpec server : config.readAll()) {
            if (!server.enabled()) {
                continue;
            }
            if (!server.isHttp()) {
                LOG.warnf("MCP server '%s' uses transport '%s'; only http/sse is supported in v0.5 "
                        + "(stdio is a documented follow-up, Risk #9). Skipping.", server.id(),
                        server.transport());
                continue;
            }
            live.add(server.id());
            try {
                McpServerConnection connection = connection(server);
                for (McpTool tool : connection.listTools()) {
                    specs.add(toToolSpec(server.id(), tool));
                }
            } catch (RuntimeException e) {
                // Best-effort: an unreachable server contributes no tools but never fails the materialization.
                LOG.warnf("MCP server '%s' is unreachable (%s); skipping its tools.", server.id(),
                        e.getMessage());
            }
        }
        withdrawAbsent(live);
        return specs;
    }

    @Override
    public String invoke(String toolName, Map<String, Object> arguments) {
        if (toolName == null || !toolName.startsWith(TOOL_PREFIX)) {
            throw new IllegalArgumentException(
                "McpBridgeToolProvider does not contribute a tool named '" + toolName
              + "'. It provides mcp.<server>.<tool> tools.");
        }
        String rest = toolName.substring(TOOL_PREFIX.length());
        int dot = rest.indexOf('.');
        if (dot <= 0 || dot == rest.length() - 1) {
            throw new IllegalArgumentException(
                "Malformed MCP tool name '" + toolName + "' (expected mcp.<server>.<tool>).");
        }
        String serverId = rest.substring(0, dot);
        String rawToolName = rest.substring(dot + 1);
        McpServerSpec server = config.readAll().stream()
                .filter(s -> s.id().equals(serverId) && s.enabled() && s.isHttp())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "MCP server '" + serverId + "' is not a registered, enabled http/sse server."));
        String argumentsJson;
        try {
            argumentsJson = mapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialize MCP tool arguments: " + e.getMessage(), e);
        }
        return connection(server).executeTool(rawToolName, argumentsJson);
    }

    private ToolSpec toToolSpec(String serverId, McpTool tool) {
        String name = TOOL_PREFIX + serverId + "." + tool.name();
        String description = tool.description() != null && !tool.description().isBlank()
                ? tool.description()
                : "MCP tool " + tool.name() + " from server " + serverId;
        return new ToolSpec(name, description, PermissionScope.MCP_REMOTE, tool.parametersJsonSchema());
    }

    /**
     * The cached connection for {@code server}, opening one if absent. The blocking connect runs OUTSIDE
     * the map lock (no IO inside {@code computeIfAbsent} → no carrier pinning, CLAUDE.md §3.8 / the M7
     * lesson); a concurrent opener loses the {@code putIfAbsent} race and closes its extra connection.
     */
    private McpServerConnection connection(McpServerSpec server) {
        McpServerConnection existing = connections.get(server.id());
        if (existing != null) {
            return existing;
        }
        McpServerConnection opened = clientFactory.connect(server); // network IO off the lock
        McpServerConnection raced = connections.putIfAbsent(server.id(), opened);
        if (raced != null) {
            closeQuietly(opened);
            return raced;
        }
        return opened;
    }

    /** Close + drop connections for servers no longer in the config (the resync withdrawal). */
    private void withdrawAbsent(Set<String> liveServerIds) {
        for (String id : Set.copyOf(connections.keySet())) {
            if (!liveServerIds.contains(id)) {
                closeQuietly(connections.remove(id));
            }
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        for (String id : Set.copyOf(connections.keySet())) {
            closeQuietly(connections.remove(id));
        }
    }

    private static void closeQuietly(McpServerConnection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (RuntimeException e) {
                // best-effort
            }
        }
    }
}
