package ai.forvum.engine.tools;

import ai.forvum.engine.model.InMemoryToolInvocationRecorder;
import ai.forvum.sdk.ToolProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Test-only factory (in the {@code tools} package, so it can wire the beans' package-private fields)
 * that assembles a fully-wired {@link ToolCallBridge} for tests in other packages — e.g. the
 * {@code SupervisorGraph} integration test — without exposing those fields on the production classes.
 */
public final class ToolTestFixtures {

    private ToolTestFixtures() {
    }

    /** A {@link ToolCallBridge} backed by a registry of {@code providers}, recording to {@code recorder}. */
    public static ToolCallBridge bridge(InMemoryToolInvocationRecorder recorder, ToolProvider... providers) {
        ToolRegistry registry = new ToolRegistry();
        for (ToolProvider provider : providers) {
            registry.register(provider);
        }
        ToolExecutor executor = new ToolExecutor();
        executor.recorder = recorder;
        ToolCallBridge bridge = new ToolCallBridge();
        bridge.registry = registry;
        bridge.toolExecutor = executor;
        bridge.mapper = new ObjectMapper();
        return bridge;
    }
}
