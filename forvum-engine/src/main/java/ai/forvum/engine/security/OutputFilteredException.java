package ai.forvum.engine.security;

import ai.forvum.core.event.FallbackReasons;

import java.util.UUID;

/**
 * Thrown by the engine's egress path when a composed {@code OutputGuard} {@code Blocked} outcome
 * suppresses an egress (ULTRAPLAN section 9.2.4, DR-6a). It carries the {@link FallbackReasons#FILTERED}
 * reason and the {@code turnId}; the engine's turn boundary catches it and emits a terminal
 * {@code ErrorEvent} with {@code code = "output_filtered"} rather than leak the candidate text.
 *
 * <p>It mirrors the <em>behavioral</em> pattern of {@code BudgetExhaustedException} — an unchecked,
 * engine-caught terminal short-circuit that intermediate layers must not be forced to declare — while
 * deliberately living in {@code forvum-engine}, not {@code forvum-core}: it is purely the engine's
 * enforcement surface, not a value contract, so it adds nothing to the Layer-0 native-reflection surface.
 */
public final class OutputFilteredException extends RuntimeException {

    private final String trip;
    private final UUID turnId;

    public OutputFilteredException(String trip, UUID turnId) {
        super("Output filtered (" + FallbackReasons.FILTERED + ") in turn " + turnId + ": " + trip);
        this.trip = trip;
        this.turnId = turnId;
    }

    /** The {@code FallbackReasons.FILTERED} telemetry token this trip surfaces. */
    public String reason() {
        return FallbackReasons.FILTERED;
    }

    /** The {@code Blocked} outcome's trip reason (why the egress was suppressed). */
    public String trip() {
        return trip;
    }

    public UUID turnId() {
        return turnId;
    }
}
