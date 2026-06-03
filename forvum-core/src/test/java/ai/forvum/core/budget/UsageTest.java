package ai.forvum.core.budget;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** The biconditional {@code cause != null <=> exhausted} and non-null fields of {@link Usage} (section 4.3.5.2). */
class UsageTest {

    private static final Spend SPEND = new Spend(BigDecimal.ZERO, 0L);

    @Test
    void constructsWhenNotExhaustedAndNoCause() {
        new Usage(SPEND, SPEND, false, null);
    }

    @Test
    void constructsWhenExhaustedWithCause() {
        new Usage(SPEND, SPEND, true, ExhaustionCause.USD_CAP_HIT);
    }

    @Test
    void rejectsExhaustedWithoutCause() {
        assertThrows(IllegalStateException.class, () -> new Usage(SPEND, SPEND, true, null));
    }

    @Test
    void rejectsCauseWhenNotExhausted() {
        assertThrows(IllegalStateException.class,
            () -> new Usage(SPEND, SPEND, false, ExhaustionCause.USD_CAP_HIT));
    }

    @Test
    void rejectsNullSpent() {
        assertThrows(IllegalStateException.class, () -> new Usage(null, SPEND, false, null));
    }

    @Test
    void rejectsNullRemaining() {
        assertThrows(IllegalStateException.class, () -> new Usage(SPEND, null, false, null));
    }
}
