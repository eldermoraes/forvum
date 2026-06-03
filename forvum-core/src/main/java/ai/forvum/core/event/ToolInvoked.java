package ai.forvum.core.event;

import java.time.Instant;

/**
 * A tool call started. {@code invocationId} mirrors {@code tool_invocations.id} (assigned by the
 * engine at row INSERT before this event is emitted).
 */
public record ToolInvoked(
    Instant timestamp,
    long invocationId,
    String toolName,
    String arguments
) implements AgentEvent {}
