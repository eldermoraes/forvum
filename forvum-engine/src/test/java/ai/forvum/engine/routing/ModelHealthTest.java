package ai.forvum.engine.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.core.ModelRef;

import org.junit.jupiter.api.Test;

/** Unit checks for the {@link ModelHealth} record: pass-rate arithmetic and validation invariants. */
class ModelHealthTest {

    private static final ModelRef REF = new ModelRef("ollama", "m");

    @Test
    void passRateIsSuccessesOverAttempts() {
        assertEquals(0.75, new ModelHealth(REF, 8, 2).passRate(), 1e-9);
    }

    @Test
    void zeroAttemptsIsTheNeutralPriorOfOne() {
        assertEquals(1.0, new ModelHealth(REF, 0, 0).passRate(), 1e-9);
    }

    @Test
    void allFailuresIsZeroPassRate() {
        assertEquals(0.0, new ModelHealth(REF, 5, 5).passRate(), 1e-9);
    }

    @Test
    void nullRefRejected() {
        assertThrows(IllegalStateException.class, () -> new ModelHealth(null, 1, 0));
    }

    @Test
    void negativeAttemptsRejected() {
        assertThrows(IllegalStateException.class, () -> new ModelHealth(REF, -1, 0));
    }

    @Test
    void failuresExceedingAttemptsRejected() {
        assertThrows(IllegalStateException.class, () -> new ModelHealth(REF, 3, 4));
    }
}
