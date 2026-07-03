package ai.forvum.core.budget;

/**
 * Which cap tripped a budget exhaustion: a {@link CostBudget} dimension (or both), or the per-turn
 * tool-loop cap ({@code toolBudget}, ULTRAPLAN section 5.5 — enforced at the tool-execution
 * boundary, #169).
 */
public enum ExhaustionCause {
    USD_CAP_HIT,
    TOKEN_CAP_HIT,
    BOTH_CAPS_HIT,
    TOOL_CAP_HIT
}
