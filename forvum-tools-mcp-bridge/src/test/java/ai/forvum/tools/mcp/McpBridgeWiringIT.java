package ai.forvum.tools.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.sdk.ToolProvider;

import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Verifies the MCP bridge WIRES under Quarkus: ArC discovers it as a {@link ToolProvider} bean, the
 * {@code DefaultMcpClientFactory} ({@code McpClientFactory}) and {@code McpServerConfig} collaborators
 * inject, and the {@code quarkus-langchain4j-mcp} extension is on the classpath without breaking boot
 * (the native-relevant CDI path). With no {@code mcp-servers/}, {@code tools()} is empty and no server is
 * connected — the inert posture the CI native no-config smoke depends on. Boots Quarkus in-JVM; runs under
 * Surefire (headless library, CLAUDE.md §4 exception).
 */
@QuarkusTest
class McpBridgeWiringIT {

    @Inject
    ToolProvider provider;   // resolves to the single McpBridgeToolProvider bean

    @Inject
    McpClientFactory clientFactory;   // resolves to DefaultMcpClientFactory

    @Test
    void beanIsDiscoveredWithTheExpectedExtensionId() {
        assertNotNull(provider);
        assertEquals("mcp", provider.extensionId());
    }

    @Test
    void toolsAreEmptyWithNoConfiguredServers() {
        // The test JVM's forvum.home points at an absent mcp-servers/ dir, so the bridge surfaces nothing
        // and never opens a connection (the no-config inert contract).
        assertTrue(provider.tools().isEmpty(), "an unconfigured MCP bridge contributes no tools");
    }

    @Test
    void realFactoryBuildsTheQuarkusTransportWithoutAClasspathLinkageError() {
        // The PRODUCTION DefaultMcpClientFactory builds a QuarkusHttpMcpTransport + DefaultMcpClient. A
        // regression to the standalone langchain4j HttpMcpTransport would throw NoClassDefFoundError
        // (okhttp3.sse) at build/connect — an Error, NOT a RuntimeException — so it would NOT satisfy this
        // assertThrows and the test goes red. Against a refused loopback the round-trip fails fast with a
        // RuntimeException, which proves the transport classes resolve. (The no-config wiring IT never
        // connects, so only this connecting test guards the §7 native-transport mandate.)
        assertNotNull(clientFactory);
        McpServerSpec refused = new McpServerSpec("probe", true, "http", "http://127.0.0.1:1/sse", Map.of());
        assertThrows(RuntimeException.class, () -> {
            try (McpServerConnection conn = clientFactory.connect(refused)) {
                conn.listTools();
            }
        });
    }
}
