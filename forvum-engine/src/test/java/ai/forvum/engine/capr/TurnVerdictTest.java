package ai.forvum.engine.capr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * {@link TurnVerdict} invariants (#195): the normalized {@code [0,1]} score contract rejects NaN
 * explicitly (repo lesson [P2-5] — NaN slips a naive range check) and out-of-range values, and the
 * reason must be non-blank. Property-style over curated edges plus seeded-random in-range scores.
 */
class TurnVerdictTest {

    @Test
    void acceptsAPassAtTheBounds() {
        assertEquals(1.0, new TurnVerdict(true, 1.0, "judge: PASS").score());
        assertEquals(0.0, new TurnVerdict(false, 0.0, "judge: FAIL").score());
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, -0.0001, -1.0, 1.0001, 42.0,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsNaNAndOutOfRangeScores(double score) {
        assertThrows(IllegalStateException.class, () -> new TurnVerdict(true, score, "reason"));
    }

    @ParameterizedTest
    @MethodSource("blankReasons")
    void rejectsABlankReason(String reason) {
        assertThrows(IllegalStateException.class, () -> new TurnVerdict(true, 1.0, reason));
    }

    static Stream<String> blankReasons() {
        return Stream.of(null, "", "   ", "\n\t");
    }

    @Test
    void acceptsSeededRandomInRangeScores() {
        Random random = new Random(195L); // fixed seed keeps failures reproducible
        for (int i = 0; i < 100; i++) {
            double score = random.nextDouble(); // [0, 1)
            TurnVerdict verdict = new TurnVerdict(false, score, "r-" + i);
            assertEquals(score, verdict.score());
            assertFalse(verdict.passed());
        }
    }

    @Test
    void failureClassTokensAreCompatibleWithFailureClass() {
        assertEquals("retryable",
                TurnJudge.classToken(ai.forvum.engine.model.FailureClass.RETRYABLE));
        assertEquals("non_retryable",
                TurnJudge.classToken(ai.forvum.engine.model.FailureClass.NON_RETRYABLE));
        assertEquals("unknown",
                TurnJudge.classToken(ai.forvum.engine.model.FailureClass.UNKNOWN));
        assertTrue(List.of("retryable", "non_retryable", "unknown")
                .contains(TurnJudge.classToken(ai.forvum.engine.model.FailureClass.UNKNOWN)));
    }
}
