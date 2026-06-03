package ai.forvum.core.budget;

import java.math.BigDecimal;

/** A (usd, tokens) spend pair. Either dimension may be null (opt-out); neither may be negative. */
public record Spend(BigDecimal usd, Long tokens) {
    public Spend {
        if (usd != null && usd.signum() < 0) {
            throw new IllegalStateException(
                "Spend usd must be null or non-negative. Got: " + usd
              + ". Negative values indicate either a ledger accounting "
              + "bug or an arithmetic underflow in BudgetMeter.");
        }
        if (tokens != null && tokens < 0) {
            throw new IllegalStateException(
                "Spend tokens must be null or non-negative. Got: " + tokens
              + ". Negative values indicate either a ledger accounting "
              + "bug or an arithmetic underflow in BudgetMeter.");
        }
    }
}
