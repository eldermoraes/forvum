package ai.forvum.engine.tools;

import ai.forvum.core.InvocationStatus;
import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentIdentity;
import ai.forvum.engine.model.ToolInvocation;
import ai.forvum.engine.model.ToolInvocationRecorder;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;
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
 *
 * <p><strong>P2-11 RBAC:</strong> belt membership is the first gate; a second, orthogonal gate checks
 * the calling identity's effective scopes (the union of its roles' {@code PermissionScope} sets, bound
 * at turn entry on {@link CurrentIdentity#CURRENT_EFFECTIVE_SCOPES}). A tool in the belt whose
 * {@link ToolSpec#requiredScope()} is outside those scopes is refused with {@link PermissionDeniedException}
 * and audited {@code denied} — the same outcome as a belt miss, a distinct message. When the scope
 * binding is absent (a caller outside a turn entry) the belt remains the sole gate; every production turn
 * entry binds the scopes, so the gate is always active in production.
 */
@ApplicationScoped
public class ToolExecutor {

    @Inject
    ToolInvocationRecorder recorder;

    /**
     * Run {@code toolName} on behalf of {@code agentId} if {@code belt} permits it AND the caller's
     * effective scopes grant the tool's required scope, auditing the outcome.
     *
     * @param belt    the agent's filtered tool belt — membership is the first capability grant
     * @param action  produces the tool's result; only invoked when the call is permitted
     * @return the tool's result
     * @throws PermissionDeniedException if the tool is not in {@code belt}, or its required scope is
     *         outside the caller's bound effective scopes (the action is not run in either case)
     */
    @WithSpan("forvum.tool.call")
    public String execute(String sessionId, AgentId agentId, List<ToolSpec> belt,
            String toolName, String arguments, Supplier<String> action) {
        // §3.6 baseline: name the tool span + mark its carrier (no-op when the SDK is disabled, the default).
        Span.current()
                .setAttribute("forvum.tool.name", toolName)
                .setAttribute("forvum.agent.id", agentId.value())
                .setAttribute("thread.is_virtual", Thread.currentThread().isVirtual());
        long createdAt = System.currentTimeMillis();
        ToolSpec tool = belt.stream().filter(spec -> spec.name().equals(toolName)).findFirst().orElse(null);
        if (tool == null) {
            recorder.record(new ToolInvocation(sessionId, agentId.value(), toolName, arguments,
                    null, InvocationStatus.DENIED, null, createdAt));
            throw new PermissionDeniedException(
                    "Agent '" + agentId.value() + "' is not permitted to call tool '" + toolName
                  + "': it is not in the agent's tool belt (its allowedTools globs do not select it). "
                  + "Grant it by adding a matching glob to the agent's allowedTools.");
        }
        if (CurrentIdentity.CURRENT_EFFECTIVE_SCOPES.isBound()) {
            Set<PermissionScope> effectiveScopes = CurrentIdentity.CURRENT_EFFECTIVE_SCOPES.get();
            if (!effectiveScopes.contains(tool.requiredScope())) {
                recorder.record(new ToolInvocation(sessionId, agentId.value(), toolName, arguments,
                        null, InvocationStatus.DENIED, null, createdAt));
                throw new PermissionDeniedException(
                        "Agent '" + agentId.value() + "' is not permitted to call tool '" + toolName
                      + "': it requires scope " + tool.requiredScope() + " but the calling identity's role"
                      + " grants only " + effectiveScopes + ". Grant it by adding " + tool.requiredScope()
                      + " to one of the identity's roles (roles/<role>.json).");
            }
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
