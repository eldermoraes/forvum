package ai.forvum.engine.session.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.engine.persistence.MessageEntity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Plain unit checks (no Quarkus boot) for the compaction value types: {@link CompactionPolicy}'s
 * canonical-constructor invariants and {@link SessionCompactor#estimateTokens}'s measured-vs-fallback
 * token accounting.
 */
class CompactionPolicyTest {

    @Test
    void acceptsAValidFloorAndRetainBudget() {
        CompactionPolicy p = new CompactionPolicy(8000, 6000);
        assertEquals(8000, p.reserveFloorTokens());
        assertEquals(6000, p.retainTokens());
    }

    @ParameterizedTest
    @CsvSource({
            "0,    100",   // non-positive floor
            "-1,   100",   // negative floor
            "100,  0",     // non-positive retain
            "100,  -5",    // negative retain
            "100,  200"    // retain > floor (would never let a pass make progress)
    })
    void rejectsInvalidThresholds(int floor, int retain) {
        assertThrows(IllegalArgumentException.class, () -> new CompactionPolicy(floor, retain));
    }

    @Test
    void estimateTokensPrefersTheMeasuredCount() {
        MessageEntity m = message("anything at all", 42);
        assertEquals(42, SessionCompactor.estimateTokens(m), "the measured tokens column wins when present");
    }

    @Test
    void estimateTokensFallsBackToCharsOverFour() {
        MessageEntity m = message("abcdefgh", null); // 8 chars -> 2 tokens
        assertEquals(2, SessionCompactor.estimateTokens(m));
    }

    @Test
    void estimateTokensIsAtLeastOneForAnyContent() {
        MessageEntity m = message("ab", null); // 2 chars / 4 = 0 -> clamped to 1
        assertEquals(1, SessionCompactor.estimateTokens(m));
    }

    private static MessageEntity message(String content, Integer tokens) {
        MessageEntity m = new MessageEntity();
        m.content = content;
        m.tokens = tokens;
        return m;
    }
}
