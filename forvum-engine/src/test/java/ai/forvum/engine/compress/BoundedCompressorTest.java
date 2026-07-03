package ai.forvum.engine.compress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.budget.BudgetExhaustedException;
import ai.forvum.core.budget.ExhaustionCause;
import ai.forvum.engine.session.compaction.Summarizer;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #176 — the shared bounded-compression policy: a summarizer failure (throw / timeout / null / blank /
 * over-limit) must NEVER reinsert the raw oversized content; it produces a DETERMINISTIC bounded
 * fallback (truncate to the output budget + a fixed marker) whose length never exceeds the boundary,
 * and it classifies the failure for diagnostics without a raw payload.
 */
class BoundedCompressorTest {

    // threshold 100 → maxOutput 100, maxInput 400; large enough to hold the fixed marker + kept content.
    private static final CompressionBudget BUDGET = CompressionBudget.fromThreshold(100);

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    private static Summarizer counting(AtomicInteger calls, Summarizer delegate) {
        return contents -> {
            calls.incrementAndGet();
            return delegate.summarize(contents);
        };
    }

    @Test
    void contentAtOrBelowThresholdPassesThroughUnchangedWithoutCallingTheModel() {
        AtomicInteger calls = new AtomicInteger();
        String content = repeat('a', 100); // == threshold, not above it
        CompressionResult r = BoundedCompressor.compress(content, BUDGET, counting(calls, c -> "SUMMARY"));
        assertEquals(CompressionOutcome.PASSED_THROUGH, r.outcome());
        assertSame(content, r.text(), "an at/below-threshold hit rides through untouched");
        assertEquals(0, calls.get(), "the model is not called below the threshold");
    }

    @Test
    void aSuccessfulSummaryWithinBudgetIsUsed() {
        String content = repeat('a', 250);
        CompressionResult r = BoundedCompressor.compress(content, BUDGET, c -> "SHORT_SUMMARY");
        assertEquals(CompressionOutcome.COMPRESSED, r.outcome());
        assertEquals("SHORT_SUMMARY", r.text());
    }

    @Test
    void aThrowingSummarizerProducesABoundedFallbackNotTheRawContent() {
        String content = repeat('a', 250);
        CompressionResult r = BoundedCompressor.compress(content, BUDGET, c -> {
            throw new RuntimeException("proxy down");
        });
        assertEquals(CompressionOutcome.FALLBACK_EXCEPTION, r.outcome());
        assertTrue(r.text().length() <= BUDGET.maxOutputChars(), "fallback stays within the output budget");
        assertTrue(r.text().endsWith(BoundedCompressor.TRUNCATION_MARKER), "the fixed omission marker is appended");
        assertFalse(r.text().contains(content), "the raw oversized content is never reinserted whole");
    }

    @Test
    void aTimeoutClassExceptionIsClassifiedAsTimeout() {
        String content = repeat('a', 250);
        CompressionResult socket = BoundedCompressor.compress(content, BUDGET, c -> {
            throw new RuntimeException(new SocketTimeoutException("read timed out"));
        });
        assertEquals(CompressionOutcome.FALLBACK_TIMEOUT, socket.outcome());
        CompressionResult concurrent = BoundedCompressor.compress(content, BUDGET, c -> {
            throw new RuntimeException(new TimeoutException("deadline"));
        });
        assertEquals(CompressionOutcome.FALLBACK_TIMEOUT, concurrent.outcome());
    }

    @Test
    void aNullSummaryProducesABoundedFallback() {
        String content = repeat('a', 250);
        CompressionResult r = BoundedCompressor.compress(content, BUDGET, c -> null);
        assertEquals(CompressionOutcome.FALLBACK_INVALID, r.outcome());
        assertTrue(r.text().length() <= BUDGET.maxOutputChars());
        assertTrue(r.text().endsWith(BoundedCompressor.TRUNCATION_MARKER));
    }

    @Test
    void aBlankSummaryProducesABoundedFallback() {
        String content = repeat('a', 250);
        CompressionResult r = BoundedCompressor.compress(content, BUDGET, c -> "   \n\t ");
        assertEquals(CompressionOutcome.FALLBACK_BLANK, r.outcome());
        assertTrue(r.text().length() <= BUDGET.maxOutputChars());
        assertTrue(r.text().endsWith(BoundedCompressor.TRUNCATION_MARKER));
    }

    @Test
    void anOversizedSummaryIsTruncatedToTheOutputBudget() {
        String content = repeat('a', 250);
        String hugeSummary = repeat('b', 500); // the model "summarized" to something still over maxOutput
        CompressionResult r = BoundedCompressor.compress(content, BUDGET, c -> hugeSummary);
        assertEquals(CompressionOutcome.FALLBACK_OVER_LIMIT, r.outcome());
        assertTrue(r.text().length() <= BUDGET.maxOutputChars(), "an over-limit summary is bounded");
        assertTrue(r.text().startsWith("b"), "the bounded text is the SUMMARY (better signal), not the raw content");
        assertTrue(r.text().endsWith(BoundedCompressor.TRUNCATION_MARKER));
    }

    @Test
    void contentAboveTheInputBudgetSkipsTheModelAndBoundsIt() {
        AtomicInteger calls = new AtomicInteger();
        String content = repeat('a', 5_000); // > maxInput (400) — must not go to the model
        CompressionResult r = BoundedCompressor.compress(content, BUDGET, counting(calls, c -> "SUMMARY"));
        assertEquals(CompressionOutcome.FALLBACK_INPUT_OVER_LIMIT, r.outcome());
        assertEquals(0, calls.get(), "an over-input-budget hit never invokes the (potentially expensive) model");
        assertTrue(r.text().length() <= BUDGET.maxOutputChars(), "bounded memory use — no window overflow");
    }

    @Test
    void theTruncationMarkerIsOurFixedLiteralNotDerivedFromContent() {
        String content = "ATTACKER_MARKER " + repeat('a', 250);
        CompressionResult r = BoundedCompressor.compress(content, BUDGET, c -> {
            throw new RuntimeException("down");
        });
        assertTrue(r.text().endsWith(BoundedCompressor.TRUNCATION_MARKER), "marker is our fixed literal");
        // the marker itself carries no delimiter and no attacker-controlled marker text
        assertFalse(BoundedCompressor.TRUNCATION_MARKER.contains("retrieved_memory"));
        assertFalse(BoundedCompressor.TRUNCATION_MARKER.contains("ATTACKER_MARKER"));
    }

    @Test
    void aClosingDelimiterIsNeverEmittedByTheFallbackItself() {
        // The fallback truncates and appends only the fixed marker; it introduces no closing delimiter of
        // its own. (Neutralizing a delimiter that rides INSIDE the untrusted content is RetrievedMemory.frame's
        // job, which runs after this — proven in the graph tests.)
        String content = repeat('a', 250);
        CompressionResult r = BoundedCompressor.compress(content, BUDGET, c -> {
            throw new RuntimeException("down");
        });
        assertFalse(r.text().substring(r.text().length() - BoundedCompressor.TRUNCATION_MARKER.length())
                .contains("</"), "the appended marker carries no closing tag");
    }

    @Test
    void aBudgetExhaustedExceptionIsRethrownNotSwallowedIntoAFallback() {
        String content = repeat('a', 250);
        UUID turn = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertThrows(BudgetExhaustedException.class, () ->
                BoundedCompressor.compress(content, BUDGET, c -> {
                    throw new BudgetExhaustedException(ExhaustionCause.USD_CAP_HIT, turn);
                }));
        // even buried in a cause chain
        assertThrows(BudgetExhaustedException.class, () ->
                BoundedCompressor.compress(content, BUDGET, c -> {
                    throw new RuntimeException(new BudgetExhaustedException(ExhaustionCause.USD_CAP_HIT, turn));
                }));
    }

    @Test
    void aNullContentIsHandledAsAnEmptyPassThrough() {
        CompressionResult r = BoundedCompressor.compress(null, BUDGET, c -> "SUMMARY");
        assertEquals(CompressionOutcome.PASSED_THROUGH, r.outcome());
        assertNotNull(r.text());
        assertTrue(r.text().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {101, 200, 399, 401, 1000, 50_000})
    void everyFallbackPathRespectsTheOutputBudgetAcrossInputSizes(int size) {
        String content = repeat('a', size);
        for (Summarizer failing : List.<Summarizer>of(
                c -> { throw new RuntimeException("x"); },
                c -> "   ",
                c -> repeat('b', 10_000))) {
            CompressionResult r = BoundedCompressor.compress(content, BUDGET, failing);
            assertTrue(r.text().length() <= BUDGET.maxOutputChars(),
                    "size=" + size + " outcome=" + r.outcome() + " must stay within maxOutput");
        }
    }

    @Test
    void fromThresholdDerivesMaxOutputAndMaxInputWithoutOverflow() {
        CompressionBudget b = CompressionBudget.fromThreshold(8000);
        assertEquals(8000, b.thresholdChars());
        assertEquals(8000, b.maxOutputChars());
        assertEquals(32_000, b.maxInputChars());
        // near Integer.MAX_VALUE the input multiplier must clamp, not overflow to a negative
        CompressionBudget huge = CompressionBudget.fromThreshold(Integer.MAX_VALUE - 1);
        assertTrue(huge.maxInputChars() >= huge.thresholdChars(), "maxInput never wraps below threshold");
    }

    @Test
    void whenTheOutputBudgetIsSmallerThanTheMarkerTheResultIsHardClampedToTheBudget() {
        CompressionBudget tiny = CompressionBudget.fromThreshold(20); // maxOutput 20 < the 60-char marker
        String content = repeat('a', 50); // above threshold 20, below maxInput 80 → the model IS called
        CompressionResult r = BoundedCompressor.compress(content, tiny, c -> {
            throw new RuntimeException("down");
        });
        assertEquals(CompressionOutcome.FALLBACK_EXCEPTION, r.outcome());
        assertTrue(r.text().length() <= 20, "even when the marker exceeds the budget, length() <= maxOutput holds");
    }

    @Test
    void aDeterministicFallbackIsStableAcrossRuns() {
        // Same input + same failure → byte-identical bounded output (determinism for prompt-cache stability).
        String content = new Random(42).ints(300, 'a', 'z' + 1)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        Summarizer down = c -> { throw new RuntimeException("down"); };
        assertEquals(BoundedCompressor.compress(content, BUDGET, down).text(),
                BoundedCompressor.compress(content, BUDGET, down).text());
    }
}
