package ai.forvum.engine.compress;

/**
 * The outcome of a bounded-compression pass (#176), for diagnostics/metrics. A safe, content-free
 * classification: {@link #PASSED_THROUGH} and {@link #COMPRESSED} are the non-fallback paths, and each
 * {@code FALLBACK_*} names WHY the summarizer's output could not be used, so a compression failure is
 * observable (timeout vs exception vs blank vs invalid vs over-limit) without ever recording the raw
 * payload.
 */
public enum CompressionOutcome {

    /** Content was at or below the threshold — no compression was needed. */
    PASSED_THROUGH,

    /** The summarizer returned a usable summary within the output budget. */
    COMPRESSED,

    /** The summarizer threw a non-timeout exception; the content was bounded. */
    FALLBACK_EXCEPTION,

    /** The summarizer threw a timeout-class exception; the content was bounded. */
    FALLBACK_TIMEOUT,

    /** The summarizer returned a blank summary; the content was bounded. */
    FALLBACK_BLANK,

    /** The summarizer returned {@code null}; the content was bounded. */
    FALLBACK_INVALID,

    /** The summarizer returned a summary still larger than the output budget; the summary was bounded. */
    FALLBACK_OVER_LIMIT,

    /** The content exceeded the input budget, so the model was skipped entirely; the content was bounded. */
    FALLBACK_INPUT_OVER_LIMIT;

    /** Whether this outcome used the deterministic bounded fallback (any {@code FALLBACK_*}). */
    public boolean isFallback() {
        return this != PASSED_THROUGH && this != COMPRESSED;
    }
}
