package ai.forvum.engine.model;

import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.TimeoutException;

import jakarta.inject.Singleton;

import ai.forvum.core.event.FallbackReasons;

/**
 * Maps a provider exception to a {@link FailureClass} (the retry decision) and a {@link FallbackReasons}
 * token (telemetry). Classification leans on LangChain4j's own {@code RetriableException} /
 * {@code NonRetriableException} base types; anything else is {@link FailureClass#UNKNOWN} and never
 * silently retried (ULTRAPLAN section 5.4).
 */
@Singleton
public class FailureClassifier {

    public FailureClass classify(Throwable error) {
        if (error instanceof RetriableException) {
            return FailureClass.RETRYABLE;
        }
        if (error instanceof NonRetriableException) {
            return FailureClass.NON_RETRYABLE;
        }
        return FailureClass.UNKNOWN;
    }

    /** The {@link FallbackReasons} token for {@code FallbackTriggered.reason}, or {@code null} if none fits. */
    public String reason(Throwable error) {
        if (error instanceof RateLimitException) {
            return FallbackReasons.RATE_LIMIT;
        }
        if (error instanceof TimeoutException) {
            return FallbackReasons.TIMEOUT;
        }
        if (error instanceof InternalServerException) {
            return FallbackReasons.SERVER_ERROR;
        }
        return null;
    }
}
