package ai.forvum.engine.model;

import ai.forvum.core.budget.BudgetMeter;
import ai.forvum.core.budget.CostBudget;
import ai.forvum.core.budget.Usage;

import java.util.UUID;

/**
 * The Decision-8 pre-call check bundle handed to {@link FallbackChatModel} at construction (ULTRAPLAN
 * section 4.3.5.2; DR-4c DP-2: the budget rides <em>alongside</em> the chain, never on it): the agent's
 * declared {@link CostBudget}, the {@link BudgetMeter} that reads its current usage from the
 * {@code provider_calls} ledger, and the turn id carried into
 * {@link ai.forvum.core.budget.BudgetExhaustedException} for correlation ({@code null} on entries that
 * bind no turn id, e.g. cron fires). A {@code null} gate on the decorator means uncapped — no meter
 * trip at all on the hot path.
 */
public record BudgetGate(CostBudget budget, BudgetMeter meter, UUID turnId) {

    public BudgetGate {
        if (budget == null || meter == null) {
            throw new IllegalArgumentException(
                "BudgetGate requires a budget and a meter — an uncapped chain passes a null GATE "
              + "to FallbackChatModel, never a null budget/meter inside one.");
        }
    }

    /** The budget's current usage, day-scoped to {@code agentId} (Decision 10). One SQL trip. */
    Usage usage(String agentId) {
        return meter.usage(budget, agentId);
    }
}
