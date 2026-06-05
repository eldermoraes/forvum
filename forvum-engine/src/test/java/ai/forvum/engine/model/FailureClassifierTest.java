package ai.forvum.engine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnsupportedFeatureException;

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

    @Test
    void fallsBackOnProviderLevelFailures() {
        assertTrue(classifier.shouldFallback(new AuthenticationException("bad key")));
        assertTrue(classifier.shouldFallback(new RateLimitException("x")));
        assertTrue(classifier.shouldFallback(new TimeoutException("x")));
        assertTrue(classifier.shouldFallback(new InternalServerException("x")));
        assertTrue(classifier.shouldFallback(new ModelNotFoundException("x")));
        assertTrue(classifier.shouldFallback(new UnsupportedFeatureException("x")));
        assertTrue(classifier.shouldFallback(new RuntimeException("connection refused")));
    }

    @Test
    void reThrowsRequestLevelFailuresWithoutFallback() {
        assertFalse(classifier.shouldFallback(new InvalidRequestException("bad request")));
        // ContentFilteredException extends InvalidRequestException — pins the load-bearing coverage invariant.
        assertFalse(classifier.shouldFallback(new ContentFilteredException("blocked")));
    }

    @Test
    void rawHttpExceptionMirrorsTheExceptionMapperClassification() {
        // A provider that does not normalize via ExceptionMapper: request-level 4xx re-throw...
        assertFalse(classifier.shouldFallback(new HttpException(400, "bad request")));
        assertFalse(classifier.shouldFallback(new HttpException(402, "no credits")));
        assertFalse(classifier.shouldFallback(new HttpException(409, "conflict")));
        assertFalse(classifier.shouldFallback(new HttpException(422, "unprocessable")));
        // ...but the codes the mapper remaps to provider-level/retryable types fall through.
        assertTrue(classifier.shouldFallback(new HttpException(401, "unauthorized")));
        assertTrue(classifier.shouldFallback(new HttpException(403, "forbidden")));
        assertTrue(classifier.shouldFallback(new HttpException(404, "not found")));
        assertTrue(classifier.shouldFallback(new HttpException(408, "request timeout")));
        assertTrue(classifier.shouldFallback(new HttpException(429, "rate limited")));
        assertTrue(classifier.shouldFallback(new HttpException(500, "server error")));
    }
}
