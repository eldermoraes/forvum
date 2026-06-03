package ai.forvum.core.event;

import java.time.Instant;
import java.util.UUID;

/** Terminal success event for a turn, carrying its {@code turnId} (section 4.3.1) and final message. */
public record Done(
    Instant timestamp,
    UUID turnId,
    String finalMessage
) implements AgentEvent {}
