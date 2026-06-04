package ai.forvum.engine.model;

/**
 * The retry axis a provider failure maps to (ULTRAPLAN section 5.4). Orthogonal to the user-facing
 * {@code FallbackReasons} token: this drives the fallback decision, the reason token drives telemetry.
 */
public sealed interface FailureClass {

    FailureClass RETRYABLE = new Retryable();
    FailureClass NON_RETRYABLE = new NonRetryable();
    FailureClass UNKNOWN = new Unknown();

    /** Only a retryable failure advances the chain; non-retryable and unknown are surfaced immediately. */
    default boolean isRetryable() {
        return this instanceof Retryable;
    }

    /** Transient fault (rate limit, timeout, 5xx) — try the next link. */
    record Retryable() implements FailureClass {
    }

    /** Permanent fault (auth, bad request, model not found) — do not retry. */
    record NonRetryable() implements FailureClass {
    }

    /** Unclassified fault — treated as non-retryable so a never-classified error is never silently retried. */
    record Unknown() implements FailureClass {
    }
}
