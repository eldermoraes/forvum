package ai.forvum.core.event;

import java.time.Instant;

/**
 * Events emitted by the agent runtime during a turn's execution.
 *
 * <p>A {@code switch} over this sealed interface compiles without a {@code default} branch:
 * the six permits are exhaustive by design (ULTRAPLAN section 4.3.2).
 *
 * <p>{@link #timestamp()} is the time of event creation, not of emission
 * or delivery to subscribers. Consumers monitoring end-to-end latency must
 * use their own receipt timestamp.
 */
public sealed interface AgentEvent
    permits TokenDelta, ToolInvoked, ToolResult, FallbackTriggered, Done, ErrorEvent {

    Instant timestamp();
}
