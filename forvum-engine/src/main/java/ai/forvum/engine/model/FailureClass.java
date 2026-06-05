package ai.forvum.engine.model;

/**
 * The retry/telemetry axis a provider failure maps to (ULTRAPLAN section 5.4). Orthogonal to the
 * user-facing {@code FallbackReasons} token: this is the retry signal; the reason token drives
 * telemetry. The fallback-advance decision (whether the chain moves to the next link) is made by
 * {@link FailureClassifier#shouldFallback(Throwable)}, not by {@code isRetryable()}.
 */
public sealed interface FailureClass {

    FailureClass RETRYABLE = new Retryable();
    FailureClass NON_RETRYABLE = new NonRetryable();
    FailureClass UNKNOWN = new Unknown();

    /**
     * Whether the failure is transient and could be retried (rate limit, timeout, 5xx). Used as the
     * retry/telemetry signal. Not used for the fallback-advance decision — see
     * {@link FailureClassifier#shouldFallback(Throwable)}.
     */
    default boolean isRetryable() {
        return this instanceof Retryable;
    }

    /** Transient fault (rate limit, timeout, 5xx) — eligible for retry. */
    record Retryable() implements FailureClass {
    }

    /** Permanent fault (auth, bad request, model not found) — not eligible for retry. */
    record NonRetryable() implements FailureClass {
    }

    /** Unclassified fault — treated as non-retryable (never silently retried). */
    record Unknown() implements FailureClass {
    }
}
