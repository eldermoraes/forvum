package ai.forvum.core.event;

import java.time.Instant;

import ai.forvum.core.ModelRef;

/**
 * The model chain advanced from {@code failed} to {@code next}. {@code reason} is populated from
 * {@link FallbackReasons} constants only (migration to a {@code FailureClass} enum is scheduled for M8).
 */
public record FallbackTriggered(
    Instant timestamp,
    ModelRef failed,
    ModelRef next,
    String reason
) implements AgentEvent {}
