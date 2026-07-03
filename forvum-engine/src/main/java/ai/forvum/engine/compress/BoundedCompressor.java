package ai.forvum.engine.compress;

import ai.forvum.core.budget.BudgetExhaustedException;
import ai.forvum.engine.session.compaction.Summarizer;

import java.util.List;
import java.util.concurrent.TimeoutException;

import org.jboss.logging.Logger;

/**
 * #176 — the ONE shared, auditable bounded-compression policy for every place the graph folds oversized
 * untrusted context through the small-and-fast proxy {@link Summarizer} before it re-enters the
 * supervisor window (retrieved memory, worker digests). A summarizer failure — a throw, a timeout, a
 * {@code null}/blank return, or a summary that is itself over budget — must NEVER reinsert the raw
 * oversized content (the pre-#176 fail-open bug that let adversarial/oversized memory or worker output
 * overflow the window, displace instructions, or amplify injection). Instead it produces a DETERMINISTIC
 * bounded fallback: truncate to {@code maxOutputChars} and append a fixed omission marker, so the text
 * that crosses the boundary is always within budget, the untrusted-data framing stays intact, and the
 * failure is classified for diagnostics WITHOUT recording the raw payload.
 *
 * <p>A {@link BudgetExhaustedException} is re-thrown unchanged (a cost/tool hard stop must abort the turn
 * per #169, never degrade to a fallback — so budget exhaustion can never trigger a compression fallback
 * at all).
 *
 * <p>Stateless and pure (String operations only) — native-safe, no reflection. The {@link Summarizer} is
 * passed in by the call-site so a test can inject a deterministic stub and the graph keeps its single
 * injected instance.
 */
public final class BoundedCompressor {

    private static final Logger LOG = Logger.getLogger(BoundedCompressor.class);

    /** Hop cap on cause-chain walks, matching {@code SupervisorGraph.asGraphFailure}. */
    private static final int MAX_CAUSE_HOPS = 50;

    /**
     * The fixed omission marker appended to any truncated fallback. OUR literal — never derived from the
     * (untrusted) content — carrying no delimiter, so it cannot be mistaken for an instruction nor close a
     * data block. The exact omitted-char count goes to the safe log, never into the prompt.
     */
    public static final String TRUNCATION_MARKER =
            "\n[Forvum: content truncated - proxy compression unavailable]";

    private BoundedCompressor() {
    }

    /**
     * Compress {@code content} through {@code summarizer} when it exceeds the budget's threshold, bounding
     * every failure path. Never returns text longer than {@code budget.maxOutputChars()}. Re-throws a
     * {@link BudgetExhaustedException} found anywhere in the summarizer's cause chain.
     */
    public static CompressionResult compress(String content, CompressionBudget budget, Summarizer summarizer) {
        if (content == null) {
            return new CompressionResult("", CompressionOutcome.PASSED_THROUGH);
        }
        if (budget.thresholdChars() <= 0 || content.length() <= budget.thresholdChars()) {
            return new CompressionResult(content, CompressionOutcome.PASSED_THROUGH);
        }
        // Too large to even send to the proxy model: bound it WITHOUT a (potentially expensive) call —
        // the bounded-memory / no-unbounded-fallback guarantee for adversarially huge content.
        if (content.length() > budget.maxInputChars()) {
            return fallback(content, budget, CompressionOutcome.FALLBACK_INPUT_OVER_LIMIT);
        }
        String summary;
        try {
            summary = summarizer.summarize(List.of(content));
        } catch (RuntimeException e) {
            BudgetExhaustedException exhaustion = findBudgetExhaustion(e);
            if (exhaustion != null) {
                throw exhaustion; // #169 — re-throw the hard stop AS ITSELF, never a fallback
            }
            CompressionOutcome reason = isTimeout(e)
                    ? CompressionOutcome.FALLBACK_TIMEOUT : CompressionOutcome.FALLBACK_EXCEPTION;
            return fallback(content, budget, reason);
        }
        if (summary == null) {
            return fallback(content, budget, CompressionOutcome.FALLBACK_INVALID);
        }
        if (summary.isBlank()) {
            return fallback(content, budget, CompressionOutcome.FALLBACK_BLANK);
        }
        if (summary.length() > budget.maxOutputChars()) {
            // The model returned something, but still over budget — bound the SUMMARY (better signal than
            // the raw content it was trying to compress).
            return fallback(summary, budget, CompressionOutcome.FALLBACK_OVER_LIMIT);
        }
        return new CompressionResult(summary, CompressionOutcome.COMPRESSED);
    }

    /** Truncate {@code text} to the output budget, append the fixed marker, and log a content-free event. */
    private static CompressionResult fallback(String text, CompressionBudget budget, CompressionOutcome reason) {
        String bounded = truncate(text, budget.maxOutputChars());
        // Safe metric/event: reason + sizes only — NEVER the raw payload (privacy contract, acceptance #5).
        LOG.warnf("Bounded compression fallback (%s): input=%d chars, output=%d chars, boundary=%d chars",
                reason, text.length(), bounded.length(), budget.maxOutputChars());
        return new CompressionResult(bounded, reason);
    }

    /**
     * Cut {@code text} to at most {@code max} chars: keep the leading chars and append {@link #TRUNCATION_MARKER}.
     * When the budget is smaller than the marker itself, hard-clamp so the {@code length <= max} guarantee
     * still holds. A cut can never escape a delimiter — a delimiter must be complete, and any complete
     * delimiter surviving inside the kept chars is stripped downstream by {@code RetrievedMemory.frame}.
     */
    private static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text; // already within budget (defensive; callers pass over-budget text)
        }
        int keep = Math.max(0, max - TRUNCATION_MARKER.length());
        String out = text.substring(0, Math.min(keep, text.length())) + TRUNCATION_MARKER;
        return out.length() <= max ? out : out.substring(0, max);
    }

    /** The {@link BudgetExhaustedException} anywhere in {@code t}'s cause chain, or {@code null}. */
    private static BudgetExhaustedException findBudgetExhaustion(Throwable t) {
        for (int hops = 0; t != null && hops < MAX_CAUSE_HOPS; hops++, t = t.getCause()) {
            if (t instanceof BudgetExhaustedException budget) {
                return budget;
            }
        }
        return null;
    }

    /** Whether a timeout-class exception appears anywhere in {@code t}'s cause chain. */
    private static boolean isTimeout(Throwable t) {
        for (int hops = 0; t != null && hops < MAX_CAUSE_HOPS; hops++, t = t.getCause()) {
            if (t instanceof TimeoutException || t.getClass().getSimpleName().endsWith("TimeoutException")) {
                return true;
            }
        }
        return false;
    }
}
