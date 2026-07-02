package ai.forvum.engine.tools;

import ai.forvum.core.budget.BudgetExhaustedException;
import ai.forvum.core.budget.ExhaustionCause;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The per-turn tool-loop budget (ULTRAPLAN section 5.5: {@code toolBudget} caps how many tool
 * executions one turn may run; enforced by #169). {@code Agent.respond} binds one instance around the
 * supervisor graph on {@link #CURRENT_TOOL_BUDGET} — the same {@code ScopedValue} seam as
 * {@code CURRENT_EFFECTIVE_SCOPES} — and {@link ToolExecutor} consumes it AFTER the belt/RBAC/approval
 * gates pass and BEFORE the action runs, so a denied/declined call never consumes budget while every
 * authorized attempt (even one whose action then fails) consumes exactly once. Enforce-iff-bound: an
 * unbound read (a caller outside a turn entry, or an uncapped persona) leaves the loop governed only by
 * the graph's {@code MAX_ROUNDS} liveness backstop.
 *
 * <p>Atomic: {@link AtomicLong#incrementAndGet()} hands out at most {@code cap} grants across any
 * number of threads — no {@code synchronized} (section 3.8).
 */
public final class TurnToolBudget {

    /** Bound at the turn entry ({@code Agent.respond}) when the persona declares a {@code toolBudget}. */
    public static final ScopedValue<TurnToolBudget> CURRENT_TOOL_BUDGET = ScopedValue.newInstance();

    private final long cap;
    private final UUID turnId;
    private final AtomicLong used = new AtomicLong();

    public TurnToolBudget(long cap, UUID turnId) {
        if (cap < 0) {
            throw new IllegalArgumentException(
                "toolBudget cap must be non-negative. Got: " + cap
              + " — the Persona constructor rejects negative caps, so this indicates a wiring bug.");
        }
        this.cap = cap;
        this.turnId = turnId;
    }

    /**
     * Consume one tool execution, throwing once the turn's cap is exceeded. The grant is a single
     * atomic increment, so concurrent callers can never over-consume — exactly {@code cap} grants
     * succeed, and every later call throws.
     *
     * @throws BudgetExhaustedException with {@link ExhaustionCause#TOOL_CAP_HIT} when the cap is spent
     */
    public void consumeOne() {
        if (used.incrementAndGet() > cap) {
            throw new BudgetExhaustedException(ExhaustionCause.TOOL_CAP_HIT, turnId);
        }
    }
}
