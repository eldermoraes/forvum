package ai.forvum.tools.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.tools.mcp.McpServerConnection.McpTool;

import io.quarkus.runtime.ShutdownEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@link McpBridgeToolProvider} over a real {@link McpServerConfig} (a {@code @TempDir} mcp-servers/) and a
 * {@link FakeMcpClientFactory} (no live server): {@code tools()} maps a server's tools to
 * {@code mcp.<id>.<tool>} specs carrying {@link PermissionScope#MCP_REMOTE}; disabled / stdio / unreachable
 * servers are skipped without failing; a server dropped from the config has its connection withdrawn (and
 * closed) on the next {@code tools()}; {@code invoke()} routes {@code mcp.<server>.<tool>} to the
 * connection and rejects malformed / unknown names.
 */
class McpBridgeToolProviderTest {

    private static final McpTool FORECAST =
            new McpTool("forecast", "Get the forecast", "{\"type\":\"object\",\"properties\":{\"city\":{}}}");
    private static final McpTool ALERTS = new McpTool("alerts", null, "{\"type\":\"object\"}");

    private static McpBridgeToolProvider provider(Path mcpDir, FakeMcpClientFactory factory) {
        McpBridgeToolProvider provider = new McpBridgeToolProvider();
        provider.config = new McpServerConfig(mcpDir);
        provider.clientFactory = factory;
        return provider;
    }

    private static void writeServer(Path mcpDir, String id, String transport, boolean enabled)
            throws IOException {
        Files.createDirectories(mcpDir);
        Files.writeString(mcpDir.resolve(id + ".json"),
                "{ \"transport\": \"" + transport + "\", \"url\": \"http://" + id + "/sse\", \"enabled\": "
              + enabled + " }");
    }

    @Test
    void toolsMapServerToolsToPrefixedSpecsWithMcpRemoteScope(@TempDir Path dir) throws Exception {
        Path mcpDir = dir.resolve("mcp-servers");
        writeServer(mcpDir, "weather", "sse", true);
        FakeMcpClientFactory factory = new FakeMcpClientFactory().withServer("weather", FORECAST, ALERTS);

        List<ToolSpec> tools = provider(mcpDir, factory).tools();

        assertEquals(2, tools.size());
        ToolSpec forecast = tools.stream().filter(t -> t.name().equals("mcp.weather.forecast"))
                .findFirst().orElseThrow();
        assertEquals(PermissionScope.MCP_REMOTE, forecast.requiredScope(),
                "every remote MCP tool carries MCP_REMOTE (DR-6b §9.3 — untrusted)");
        assertEquals("Get the forecast", forecast.description());
        assertEquals("{\"type\":\"object\",\"properties\":{\"city\":{}}}", forecast.parametersJsonSchema());

        ToolSpec alerts = tools.stream().filter(t -> t.name().equals("mcp.weather.alerts"))
                .findFirst().orElseThrow();
        assertTrue(alerts.description().contains("alerts"),
                "a null tool description falls back to a synthesized one, got: " + alerts.description());
    }

    @Test
    void disabledServerContributesNoTools(@TempDir Path dir) throws Exception {
        Path mcpDir = dir.resolve("mcp-servers");
        writeServer(mcpDir, "weather", "sse", false);
        FakeMcpClientFactory factory = new FakeMcpClientFactory().withServer("weather", FORECAST);

        assertTrue(provider(mcpDir, factory).tools().isEmpty(), "a disabled server is skipped");
        assertEquals(0, factory.connectCalls.get(), "a disabled server is never connected");
    }

    @Test
    void stdioServerIsSkippedWithoutConnecting(@TempDir Path dir) throws Exception {
        Path mcpDir = dir.resolve("mcp-servers");
        writeServer(mcpDir, "local", "stdio", true);
        FakeMcpClientFactory factory = new FakeMcpClientFactory().withServer("local", FORECAST);

        assertTrue(provider(mcpDir, factory).tools().isEmpty(),
                "stdio is unsupported in v0.5 (Risk #9) — skipped");
        assertEquals(0, factory.connectCalls.get(), "an unsupported transport is never connected");
    }

    @Test
    void unreachableServerContributesNoToolsButNeverFails(@TempDir Path dir) throws Exception {
        Path mcpDir = dir.resolve("mcp-servers");
        writeServer(mcpDir, "down", "http", true);
        writeServer(mcpDir, "weather", "sse", true);
        FakeMcpClientFactory factory = new FakeMcpClientFactory()
                .failingToConnect("down")
                .withServer("weather", FORECAST);

        List<ToolSpec> tools = provider(mcpDir, factory).tools();

        assertEquals(1, tools.size(), "the reachable server's tools still surface; the down one is skipped");
        assertEquals("mcp.weather.forecast", tools.get(0).name());
    }

    @Test
    void droppedServerHasItsConnectionWithdrawnAndClosedOnNextTools(@TempDir Path dir) throws Exception {
        Path mcpDir = dir.resolve("mcp-servers");
        writeServer(mcpDir, "weather", "sse", true);
        FakeMcpClientFactory factory = new FakeMcpClientFactory().withServer("weather", FORECAST);
        McpBridgeToolProvider provider = provider(mcpDir, factory);

        provider.tools(); // opens + caches the weather connection
        FakeMcpClientFactory.FakeConnection weather = factory.opened.get("weather");
        assertFalse(weather.closed, "the connection is live while the server is configured");

        Files.delete(mcpDir.resolve("weather.json")); // operator removes the server
        List<ToolSpec> after = provider.tools();        // resync re-reads the (now empty) config

        assertTrue(after.isEmpty(), "a removed server contributes no tools");
        assertTrue(weather.closed, "the withdrawn server's cached connection is closed");
    }

    @Test
    void invokeRoutesToTheServerConnectionWithSerializedArguments(@TempDir Path dir) throws Exception {
        Path mcpDir = dir.resolve("mcp-servers");
        writeServer(mcpDir, "weather", "sse", true);
        FakeMcpClientFactory factory = new FakeMcpClientFactory()
                .withServer("weather", FORECAST)
                .withToolResult("forecast", "sunny");
        McpBridgeToolProvider provider = provider(mcpDir, factory);

        String result = provider.invoke("mcp.weather.forecast", Map.of("city", "Recife"));

        assertEquals("sunny", result);
        FakeMcpClientFactory.FakeConnection weather = factory.opened.get("weather");
        assertEquals(List.of("forecast"), weather.executed, "the RAW tool name is sent to the server");
        assertTrue(weather.argumentsJson.get(0).contains("Recife"),
                "arguments are serialized to a JSON object, got: " + weather.argumentsJson.get(0));
    }

    @Test
    void invokeReusesTheCachedConnectionAcrossToolsAndInvoke(@TempDir Path dir) throws Exception {
        Path mcpDir = dir.resolve("mcp-servers");
        writeServer(mcpDir, "weather", "sse", true);
        FakeMcpClientFactory factory = new FakeMcpClientFactory().withServer("weather", FORECAST);
        McpBridgeToolProvider provider = provider(mcpDir, factory);

        provider.tools();                          // connect #1
        provider.invoke("mcp.weather.forecast", Map.of());

        assertEquals(1, factory.connectCalls.get(), "invoke() reuses the connection tools() opened");
    }

    @Test
    void onStopClosesEveryOpenConnection(@TempDir Path dir) throws Exception {
        Path mcpDir = dir.resolve("mcp-servers");
        writeServer(mcpDir, "weather", "sse", true);
        FakeMcpClientFactory factory = new FakeMcpClientFactory().withServer("weather", FORECAST);
        McpBridgeToolProvider provider = provider(mcpDir, factory);

        provider.tools(); // opens the weather connection
        FakeMcpClientFactory.FakeConnection weather = factory.opened.get("weather");
        assertFalse(weather.closed, "live before shutdown");

        provider.onStop(new ShutdownEvent());

        assertTrue(weather.closed, "shutdown closes every open MCP connection");
    }

    @Test
    void invokeRejectsNonMcpUnknownAndMalformedNames(@TempDir Path dir) throws Exception {
        Path mcpDir = dir.resolve("mcp-servers");
        writeServer(mcpDir, "weather", "sse", true);
        McpBridgeToolProvider provider = provider(mcpDir,
                new FakeMcpClientFactory().withServer("weather", FORECAST));

        assertThrows(IllegalArgumentException.class, () -> provider.invoke("fs.read", Map.of()),
                "a non-mcp.* tool is not this provider's");
        assertThrows(IllegalArgumentException.class, () -> provider.invoke("mcp.weather", Map.of()),
                "mcp.<server> with no tool is malformed");
        assertThrows(IllegalArgumentException.class, () -> provider.invoke("mcp.unknown.tool", Map.of()),
                "an unregistered server id is rejected");
    }
}
