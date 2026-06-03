package ai.forvum.core.budget;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** {@link Spend} permits null dimensions but forbids negative ones (section 4.3.5.2). */
class SpendTest {

    @Test
    void allowsBothDimensionsNull() {
        new Spend(null, null);
    }

    @Test
    void allowsNonNegative() {
        new Spend(new BigDecimal("1.50"), 100L);
    }

    @Test
    void rejectsNegativeUsd() {
        assertThrows(IllegalStateException.class, () -> new Spend(new BigDecimal("-0.01"), null));
    }

    @Test
    void rejectsNegativeTokens() {
        assertThrows(IllegalStateException.class, () -> new Spend(null, -1L));
    }
}
