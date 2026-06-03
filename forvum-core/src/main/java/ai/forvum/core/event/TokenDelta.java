package ai.forvum.core.event;

import java.time.Instant;

import ai.forvum.core.ModelRef;

/** A streamed token chunk produced by {@code model} during generation. */
public record TokenDelta(Instant timestamp, String text, ModelRef model)
    implements AgentEvent {}
