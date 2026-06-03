package ai.forvum.core.budget;

import java.util.UUID;

/**
 * Thrown by {@code FallbackChatModel.chat(...)} when a pre-call
 * {@link BudgetMeter#usage(CostBudget)} check returns
 * {@code exhausted == true}. The exception short-circuits the
 * fallback chain for cost-driven exhaustion — no further links are
 * attempted, in contrast with retry-class {@code FallbackReasons}
 * (see §5.4) — and is caught by the engine layer, which surfaces it
 * as a terminal {@code Error} {@link ai.forvum.core.event.AgentEvent}
 * with {@code code = "budget_exhausted"} plus {@code cause} and
 * {@code turnId} attributes.
 *
 * <p>Unchecked because the only legitimate catcher is the engine
 * layer; intermediate layers should not declare {@code throws}.
 */
public final class BudgetExhaustedException extends RuntimeException {
    private final ExhaustionCause cause;
    private final UUID turnId;

    public BudgetExhaustedException(ExhaustionCause cause, UUID turnId) {
        super("Budget exhausted: " + cause + " in turn " + turnId);
        this.cause = cause;
        this.turnId = turnId;
    }

    public ExhaustionCause cause() { return cause; }
    public UUID turnId() { return turnId; }
}
