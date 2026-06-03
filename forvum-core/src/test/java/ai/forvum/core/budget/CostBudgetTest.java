package ai.forvum.core.budget;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/** Example-based invariants for {@link CostBudget} (ULTRAPLAN section 4.3.5.2). */
class CostBudgetTest {

    private static final Window WINDOW = new DayWindow(ZoneId.of("UTC"));

    @Test
    void usdOnlyConstructs() {
        new CostBudget(new BigDecimal("5.00"), null, WINDOW);
    }

    @Test
    void tokenOnlyConstructs() {
        new CostBudget(null, 1000L, WINDOW);
    }

    @Test
    void zeroCapsAreAllowed() {
        new CostBudget(BigDecimal.ZERO, 0L, WINDOW);
    }

    @Test
    void rejectsBothCapsNull() {
        assertThrows(IllegalStateException.class, () -> new CostBudget(null, null, WINDOW));
    }

    @Test
    void rejectsNullWindow() {
        assertThrows(IllegalStateException.class, () -> new CostBudget(BigDecimal.ONE, null, null));
    }
}
