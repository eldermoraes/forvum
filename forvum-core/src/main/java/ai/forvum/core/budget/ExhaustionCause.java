package ai.forvum.core.budget;

/** Which cap (or both) tripped a {@link CostBudget} exhaustion. */
public enum ExhaustionCause {
    USD_CAP_HIT,
    TOKEN_CAP_HIT,
    BOTH_CAPS_HIT
}
