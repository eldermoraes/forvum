package ai.forvum.core.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** {@link ExhaustionCause} and the two budget failure signals (section 4.3.5.2). */
class BudgetSignalsTest {

    @Test
    void exhaustionCauseHasExactlyFourConstants() {
        // The three CostBudget dimensions plus the per-turn tool-loop cap (#169).
        assertEquals(4, ExhaustionCause.values().length);
    }

    @Test
    void toolCapExhaustionRidesTheSameExceptionAsCostCaps() {
        UUID turn = UUID.randomUUID();
        BudgetExhaustedException e = new BudgetExhaustedException(ExhaustionCause.TOOL_CAP_HIT, turn);
        assertEquals(ExhaustionCause.TOOL_CAP_HIT, e.cause());
        assertTrue(e.getMessage().contains("TOOL_CAP_HIT"));
    }

    @Test
    void budgetMeterPerAgentReadDefaultsToTheUnscopedRead() {
        // The #169 per-agent overload (Decision 10) must delegate for implementations predating it.
        Usage unscoped = new Usage(new Spend(null, 1L), new Spend(null, 9L), false, null);
        BudgetMeter legacyMeter = budget -> unscoped;
        assertEquals(unscoped, legacyMeter.usage(
                new CostBudget(null, 10L, new SessionWindow("s", "a")), "any-agent"));
    }

    @Test
    void budgetExhaustedExceptionCarriesCauseAndTurn() {
        UUID turn = UUID.randomUUID();
        BudgetExhaustedException e = new BudgetExhaustedException(ExhaustionCause.BOTH_CAPS_HIT, turn);
        assertEquals(ExhaustionCause.BOTH_CAPS_HIT, e.cause());
        assertEquals(turn, e.turnId());
        assertTrue(e.getMessage().contains("BOTH_CAPS_HIT"));
    }

    @Test
    void spawnConfigurationExceptionCarriesIdsAndReason() {
        SpawnConfigurationException e = new SpawnConfigurationException("parent", "child", "reason text");
        assertEquals("parent", e.parentAgentId());
        assertEquals("child", e.childAgentId());
        assertEquals("reason text", e.getMessage());
    }
}
