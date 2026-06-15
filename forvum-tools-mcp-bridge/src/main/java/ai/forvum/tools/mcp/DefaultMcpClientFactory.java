package ai.forvum.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;

import io.quarkiverse.langchain4j.mcp.runtime.http.QuarkusHttpMcpTransport;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The production {@link McpClientFactory}: builds a langchain4j {@code DefaultMcpClient} over the
 * Quarkiverse {@code QuarkusHttpMcpTransport} (HTTP/SSE — the v0.5-supported transport) and adapts it to the
 * langchain4j-free {@link McpServerConnection} seam. This is the ONLY class that touches langchain4j/MCP
 * types, so the provider and its tests stay free of them. The transport is the EXTENSION's Vert.x-based one
 * (the {@code McpRecorder} builds its clients the same way), NOT the standalone langchain4j
 * {@code HttpMcpTransport} — that one drags in OkHttp's {@code okhttp-sse}, which is absent from the
 * classpath and not native-friendly; the Quarkus transport rides the managed Quarkus HTTP (Vert.x) stack and
 * is native-ready (the §7 mandate). API harvested from the resolved JARs (javap):
 * {@code McpClient.listTools()/executeTool()/close()}, {@code DefaultMcpClient.Builder},
 * {@code QuarkusHttpMcpTransport.Builder} ({@code sseUrl}/{@code mcpClientName}/{@code timeout}/
 * {@code headers}).
 */
@ApplicationScoped
public class DefaultMcpClientFactory implements McpClientFactory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Bounds the SSE connect / {@code listTools} round-trip. The bridge materializes tools synchronously
     * (the engine ToolRegistry calls {@code tools()} at startup), so an unreachable configured server delays
     * ONLY its own tool materialization by at most this much — boot then proceeds with that server's tools
     * absent (best-effort). One-shot commands skip materialization entirely (ToolRegistry is gated), so this
     * is paid only by a normal server boot and by an on-demand {@code mcp list}; keep the default modest.
     * Tunable for slow networks and tightened in tests.
     */
    @ConfigProperty(name = "forvum.mcp.connect-timeout-seconds", defaultValue = "5")
    int connectTimeoutSeconds;

    @Override
    public McpServerConnection connect(McpServerSpec spec) {
        QuarkusHttpMcpTransport.Builder transportBuilder = new QuarkusHttpMcpTransport.Builder()
                .sseUrl(spec.url())
                .mcpClientName(spec.id())
                .timeout(Duration.ofSeconds(connectTimeoutSeconds));
        if (spec.headers() != null && !spec.headers().isEmpty()) {
            transportBuilder.headers(spec.headers());
        }
        McpTransport transport = transportBuilder.build();
        McpClient client = new DefaultMcpClient.Builder()
                .key(spec.id())
                .transport(transport)
                .clientName("forvum")
                .clientVersion("0.1.0")
                .cacheToolList(true)
                .toolExecutionTimeout(Duration.ofSeconds(60))
                .build();
        return new ClientConnection(client);
    }

    /** Adapter wrapping a langchain4j {@link McpClient} as the narrow {@link McpServerConnection}. */
    private static final class ClientConnection implements McpServerConnection {

        private final McpClient client;

        ClientConnection(McpClient client) {
            this.client = client;
        }

        @Override
        public List<McpTool> listTools() {
            List<McpTool> tools = new ArrayList<>();
            for (ToolSpecification spec : client.listTools()) {
                tools.add(new McpTool(spec.name(), spec.description(), parametersSchema(spec)));
            }
            return tools;
        }

        @Override
        public String executeTool(String toolName, String argumentsJson) {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .name(toolName)
                    .arguments(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson)
                    .build();
            ToolExecutionResult result = client.executeTool(request);
            return result == null ? "" : result.resultText();
        }

        @Override
        public void close() {
            try {
                client.close();
            } catch (Exception e) {
                // best-effort close; the server may already be gone
            }
        }

        /** The tool's parameters as a JSON-schema string: extracted from the langchain4j-serialized spec
         *  (its {@code parameters} subtree), with a permissive object fallback. */
        private static String parametersSchema(ToolSpecification spec) {
            try {
                JsonNode parameters = MAPPER.readTree(spec.toJson()).get("parameters");
                if (parameters != null && !parameters.isNull()) {
                    return parameters.toString();
                }
            } catch (Exception e) {
                // fall through to the permissive default
            }
            return "{\"type\":\"object\"}";
        }
    }
}
