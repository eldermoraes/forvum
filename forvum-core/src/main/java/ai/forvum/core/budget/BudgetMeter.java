package ai.forvum.core.budget;

/**
 * Read-side service for querying a {@link CostBudget}'s
 * current usage.
 *
 * <p>Implementations aggregate over {@code provider_calls}
 * (§4.2) scoped by the budget's {@link Window} to produce
 * an atomic {@link Usage} snapshot in a single SQL trip.
 * The default implementation lives in the M5 persistence
 * layer (§5.x); this interface ships only the contract.
 *
 * <p>Safe to inject as a singleton CDI bean; no per-call
 * state.
 */
public interface BudgetMeter {
    Usage usage(CostBudget budget);

    /**
     * As {@link #usage(CostBudget)}, additionally scoping a {@link DayWindow} aggregation to
     * {@code agentId} — ULTRAPLAN section 4.3.5.2 Decision 10: spend is tracked independently
     * per agent, so a day-window budget aggregates only the calling agent's ledger rows. A
     * {@link SessionWindow} already carries its own {@code (sessionId, agentId)} pair and
     * ignores this parameter; a {@code null} agent id keeps the unscoped read. The default
     * delegates to the unscoped read for implementations predating the per-agent filter
     * (#169); the M5 persistence implementation overrides it.
     */
    default Usage usage(CostBudget budget, String agentId) {
        return usage(budget);
    }
}
