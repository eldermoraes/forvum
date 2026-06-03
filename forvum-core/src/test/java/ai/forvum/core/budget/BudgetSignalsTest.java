package ai.forvum.core.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** {@link ExhaustionCause} and the two budget failure signals (section 4.3.5.2). */
class BudgetSignalsTest {

    @Test
    void exhaustionCauseHasExactlyThreeConstants() {
        assertEquals(3, ExhaustionCause.values().length);
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
