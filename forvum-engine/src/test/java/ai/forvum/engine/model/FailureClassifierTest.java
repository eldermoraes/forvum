package ai.forvum.engine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;

import ai.forvum.core.event.FallbackReasons;

import org.junit.jupiter.api.Test;

/** Maps LangChain4j exceptions to {@link FailureClass} and {@link FallbackReasons} tokens (section 5.4). */
class FailureClassifierTest {

    private final FailureClassifier classifier = new FailureClassifier();

    @Test
    void retriableExceptionsAreRetryable() {
        assertTrue(classifier.classify(new RateLimitException("x")).isRetryable());
        assertTrue(classifier.classify(new TimeoutException("x")).isRetryable());
        assertTrue(classifier.classify(new InternalServerException("x")).isRetryable());
    }

    @Test
    void nonRetriableExceptionsAreNotRetryable() {
        assertEquals(FailureClass.NON_RETRYABLE, classifier.classify(new AuthenticationException("x")));
        assertFalse(classifier.classify(new ModelNotFoundException("x")).isRetryable());
        assertFalse(classifier.classify(new InvalidRequestException("x")).isRetryable());
    }

    @Test
    void otherThrowablesAreUnknownAndNeverRetried() {
        assertEquals(FailureClass.UNKNOWN, classifier.classify(new RuntimeException("weird")));
        assertFalse(classifier.classify(new RuntimeException("weird")).isRetryable());
    }

    @Test
    void reasonTokensMatchTheTransientFault() {
        assertEquals(FallbackReasons.RATE_LIMIT, classifier.reason(new RateLimitException("x")));
        assertEquals(FallbackReasons.TIMEOUT, classifier.reason(new TimeoutException("x")));
        assertEquals(FallbackReasons.SERVER_ERROR, classifier.reason(new InternalServerException("x")));
        assertNull(classifier.reason(new RuntimeException("x")));
    }
}
