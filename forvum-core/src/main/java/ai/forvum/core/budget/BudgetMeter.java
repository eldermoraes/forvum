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
}
