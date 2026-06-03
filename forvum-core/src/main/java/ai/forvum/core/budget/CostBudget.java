package ai.forvum.core.budget;

import java.math.BigDecimal;

/**
 * Two-dimensional spend cap (USD and/or tokens) aggregated over a {@link Window}. Both caps are
 * nullable but at least one must be present; exhaustion fires when any non-null cap is reached.
 * Pure data — the read-side lives on {@link BudgetMeter} (ULTRAPLAN section 4.3.5.2).
 */
public record CostBudget(BigDecimal maxUsd, Long maxTokens, Window window) {
    public CostBudget {
        if (maxUsd == null && maxTokens == null) {
            throw new IllegalStateException(
                "CostBudget must declare at least one cap (maxUsd, "
              + "maxTokens, or both). Both nulls indicates either a "
              + "config-file error or a programmatic construction bug. "
              + "Check agents/<id>.json or crons/<id>.json.");
        }
        if (maxUsd != null && maxUsd.signum() < 0) {
            throw new IllegalStateException(
                "CostBudget maxUsd must be non-negative. Got: " + maxUsd
              + ". Negative caps are nonsensical — check config "
              + "file formatting.");
        }
        if (maxTokens != null && maxTokens < 0) {
            throw new IllegalStateException(
                "CostBudget maxTokens must be non-negative. Got: " + maxTokens
              + ". Negative caps are nonsensical — check config "
              + "file formatting.");
        }
        if (window == null) {
            throw new IllegalStateException(
                "CostBudget window must be non-null. Every budget "
              + "requires an explicit time window (DayWindow or "
              + "SessionWindow). Check agents/<id>.json or "
              + "crons/<id>.json — if the timezone field was the "
              + "only window-related config, the config parser "
              + "must still construct a DayWindow with resolved "
              + "ZoneId before building CostBudget.");
        }
    }
}
