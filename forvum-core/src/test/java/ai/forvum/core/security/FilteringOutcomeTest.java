package ai.forvum.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Invariants of the sealed {@link FilteringOutcome} hierarchy (DR-6a §9.2.1). */
class FilteringOutcomeTest {

    @Test
    void allowedAndBlockedCarryTheirPayload() {
        assertEquals("hi", ((FilteringOutcome.Allowed) new FilteringOutcome.Allowed("hi")).content());
        assertEquals("leak", new FilteringOutcome.Blocked("leak").reason());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 42})
    void redactedAcceptsANonNegativeCount(int count) {
        assertEquals(count, new FilteringOutcome.Redacted("x", count).redactions());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -100, Integer.MIN_VALUE})
    void redactedRejectsANegativeCount(int count) {
        assertThrows(IllegalStateException.class, () -> new FilteringOutcome.Redacted("x", count));
    }

    @Test
    void switchOverTheSealedSetIsExhaustiveWithoutADefault() {
        FilteringOutcome o = new FilteringOutcome.Redacted("masked", 2);
        String label = switch (o) {
            case FilteringOutcome.Allowed a -> "allowed";
            case FilteringOutcome.Redacted r -> "redacted:" + r.redactions();
            case FilteringOutcome.Blocked b -> "blocked";
        };
        assertEquals("redacted:2", label);
    }
}
