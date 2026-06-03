package ai.forvum.core.event;

import java.time.Instant;

import ai.forvum.core.InvocationStatus;

/** The outcome of a previously-emitted {@link ToolInvoked}, correlated by {@code invocationId}. */
public record ToolResult(
    Instant timestamp,
    long invocationId,
    String result,
    InvocationStatus status,
    long latencyMs
) implements AgentEvent {}
