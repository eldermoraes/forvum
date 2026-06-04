package ai.forvum.core.event;

import java.time.Instant;

import ai.forvum.core.ModelRef;

/**
 * The model chain advanced from {@code failed} to {@code next}. {@code reason} is populated from
 * {@link FallbackReasons} constants only (the once-proposed migration to a {@code FailureClass} enum was
 * declined at M8 — that type is the engine-local retry axis, orthogonal to this telemetry token).
 */
public record FallbackTriggered(
    Instant timestamp,
    ModelRef failed,
    ModelRef next,
    String reason
) implements AgentEvent {}
