package ai.forvum.engine.model;

import ai.forvum.core.InvocationStatus;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One {@code tool_invocations} ledger row produced per attempted tool call (ULTRAPLAN section 4.2 / 5.3).
 * {@code result} holds the tool output (or the failure text on {@code ERROR}, null on {@code DENIED});
 * {@code status} is the outcome the engine's {@code ToolExecutor} records. Layer-2 DTO — carries the
 * real Quarkus {@code @RegisterForReflection} (the forvum-sdk re-export is inert until its build step
 * ships), mirroring {@link ProviderCall}.
 */
@RegisterForReflection
public record ToolInvocation(
        String sessionId,
        String agentId,
        String toolName,
        String arguments,
        String result,
        InvocationStatus status,
        Integer latencyMs,
        long createdAt) {
}
