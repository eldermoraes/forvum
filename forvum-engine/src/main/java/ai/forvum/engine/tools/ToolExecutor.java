package ai.forvum.engine.tools;

import ai.forvum.core.InvocationStatus;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.model.ToolInvocation;
import ai.forvum.engine.model.ToolInvocationRecorder;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.function.Supplier;

/**
 * Enforces an agent's capability boundary before invoking a tool and ledgers every attempt (ULTRAPLAN
 * section 5.3 / 5.5). Given the agent's filtered tool belt (the {@code AgentToolBelt} subset that
 * {@link ToolFilter} produced from the persona's {@code allowedTools} globs), a call to a tool outside
 * that belt is refused with {@link PermissionDeniedException} and audited {@code denied} — there is no
 * code path that bypasses the belt to grant "just this one call" access. A permitted call runs the
 * supplied action and is audited {@code ok}; a throwing action is audited {@code error} and the failure
 * is rethrown unchanged.
 *
 * <p>The action is supplied by the caller (the M18 {@code tool_loop} wires the real tool dispatch),
 * keeping this executor standalone-testable and out of {@code Agent.respond()}.
 */
@ApplicationScoped
public class ToolExecutor {

    @Inject
    ToolInvocationRecorder recorder;

    /**
     * Run {@code toolName} on behalf of {@code agentId} if {@code belt} permits it, auditing the outcome.
     *
     * @param belt    the agent's filtered tool belt — membership is the capability grant
     * @param action  produces the tool's result; only invoked when the call is permitted
     * @return the tool's result
     * @throws PermissionDeniedException if the tool is not in {@code belt} (the action is not run)
     */
    public String execute(String sessionId, AgentId agentId, List<ToolSpec> belt,
            String toolName, String arguments, Supplier<String> action) {
        long createdAt = System.currentTimeMillis();
        boolean permitted = belt.stream().anyMatch(spec -> spec.name().equals(toolName));
        if (!permitted) {
            recorder.record(new ToolInvocation(sessionId, agentId.value(), toolName, arguments,
                    null, InvocationStatus.DENIED, null, createdAt));
            throw new PermissionDeniedException(
                    "Agent '" + agentId.value() + "' is not permitted to call tool '" + toolName
                  + "': it is not in the agent's tool belt (its allowedTools globs do not select it). "
                  + "Grant it by adding a matching glob to the agent's allowedTools.");
        }
        long start = System.nanoTime();
        try {
            String result = action.get();
            recorder.record(new ToolInvocation(sessionId, agentId.value(), toolName, arguments,
                    result, InvocationStatus.OK, elapsedMillis(start), createdAt));
            return result;
        } catch (RuntimeException failure) {
            recorder.record(new ToolInvocation(sessionId, agentId.value(), toolName, arguments,
                    failure.toString(), InvocationStatus.ERROR, elapsedMillis(start), createdAt));
            throw failure;
        }
    }

    private static int elapsedMillis(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    }
}
