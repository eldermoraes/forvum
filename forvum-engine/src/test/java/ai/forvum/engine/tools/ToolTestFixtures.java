package ai.forvum.engine.tools;

import ai.forvum.engine.approval.ApprovalGate;
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

    /**
     * A {@link ToolCallBridge} backed by a registry of {@code providers}, recording to {@code recorder}. No
     * approval gate — only safe for belts of non-confirm-required tools (the executor consults the gate
     * solely when {@code ToolSpec.userConfirmRequired()} is true).
     */
    public static ToolCallBridge bridge(InMemoryToolInvocationRecorder recorder, ToolProvider... providers) {
        return bridge(recorder, null, providers);
    }

    /** As {@link #bridge(InMemoryToolInvocationRecorder, ToolProvider...)}, plus an {@link ApprovalGate}. */
    public static ToolCallBridge bridge(InMemoryToolInvocationRecorder recorder, ApprovalGate gate,
            ToolProvider... providers) {
        ToolRegistry registry = new ToolRegistry();
        for (ToolProvider provider : providers) {
            registry.register(provider);
        }
        ToolExecutor executor = new ToolExecutor();
        executor.recorder = recorder;
        executor.approvals = gate;
        ToolCallBridge bridge = new ToolCallBridge();
        bridge.registry = registry;
        bridge.toolExecutor = executor;
        bridge.mapper = new ObjectMapper();
        return bridge;
    }
}
