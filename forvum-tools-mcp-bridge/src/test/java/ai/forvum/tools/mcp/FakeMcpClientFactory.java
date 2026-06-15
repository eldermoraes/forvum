package ai.forvum.tools.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double for {@link McpClientFactory}: hands back a scripted, langchain4j-free {@link FakeConnection}
 * per server id (so the provider is exercised with no live MCP server). A server id can be configured to
 * fail connecting (to exercise the best-effort skip), and each connection records its tool calls and
 * whether it was closed (to assert routing + withdrawal/shutdown).
 */
final class FakeMcpClientFactory implements McpClientFactory {

    private final Map<String, List<McpServerConnection.McpTool>> toolsByServer = new ConcurrentHashMap<>();
    private final Map<String, String> resultByTool = new ConcurrentHashMap<>();
    private final java.util.Set<String> failing = ConcurrentHashMap.newKeySet();
    final Map<String, FakeConnection> opened = new ConcurrentHashMap<>();
    final AtomicInteger connectCalls = new AtomicInteger();

    FakeMcpClientFactory withServer(String id, McpServerConnection.McpTool... tools) {
        toolsByServer.put(id, List.of(tools));
        return this;
    }

    FakeMcpClientFactory withToolResult(String rawToolName, String result) {
        resultByTool.put(rawToolName, result);
        return this;
    }

    FakeMcpClientFactory failingToConnect(String id) {
        failing.add(id);
        return this;
    }

    @Override
    public McpServerConnection connect(McpServerSpec spec) {
        connectCalls.incrementAndGet();
        if (failing.contains(spec.id())) {
            throw new IllegalStateException("simulated connection failure to " + spec.id());
        }
        FakeConnection connection = new FakeConnection(
                toolsByServer.getOrDefault(spec.id(), List.of()), resultByTool);
        opened.put(spec.id(), connection);
        return connection;
    }

    /** A recording, scripted {@link McpServerConnection}. */
    static final class FakeConnection implements McpServerConnection {

        private final List<McpTool> tools;
        private final Map<String, String> resultByTool;
        final List<String> executed = new ArrayList<>();
        final List<String> argumentsJson = new ArrayList<>();
        boolean closed;

        FakeConnection(List<McpTool> tools, Map<String, String> resultByTool) {
            this.tools = tools;
            this.resultByTool = resultByTool;
        }

        @Override
        public List<McpTool> listTools() {
            return tools;
        }

        @Override
        public String executeTool(String toolName, String arguments) {
            executed.add(toolName);
            argumentsJson.add(arguments);
            return resultByTool.getOrDefault(toolName, "result:" + toolName);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
