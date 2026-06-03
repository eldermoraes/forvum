package ai.forvum.core.budget;

/**
 * Scope + time window over which a {@link CostBudget} is aggregated.
 *
 * <p><b>Marker interface with scope data carried by permits.</b>
 * This interface declares no methods. Each permit carries the scope
 * data relevant to its granularity ({@link java.time.ZoneId} for
 * {@link DayWindow}; {@code sessionId + agentId} for
 * {@link SessionWindow}). Consumers that must derive a persistence
 * query from a {@code Window} pattern-match on the permit type via
 * exhaustive {@code switch} — see {@link BudgetMeter} implementations
 * in the M5 persistence layer (§5.x) for the canonical pattern.
 *
 * <p><b>Extensibility contract.</b> Adding a new permit to this
 * sealed interface is a source-compatible change for code that only
 * constructs or passes {@code Window} values. Consumers that
 * pattern-match on permits via exhaustive {@code switch} will fail
 * compilation until the new permit is handled — this is the intended
 * surfacing mechanism for new cost-window policies.
 */
public sealed interface Window permits DayWindow, SessionWindow {
}
