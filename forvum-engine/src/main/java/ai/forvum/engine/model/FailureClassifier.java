package ai.forvum.engine.model;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.TimeoutException;

import jakarta.inject.Singleton;

import ai.forvum.core.event.FallbackReasons;

/**
 * Maps a provider exception to a {@link FailureClass} (the retry/telemetry axis) and a
 * {@link FallbackReasons} token (telemetry), and determines whether a failed link should fall
 * through to the next provider (ULTRAPLAN section 5.4).
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

    /**
     * Whether a failed link should fall through to the next provider. Provider-level failures (auth,
     * rate limit, timeout, 5xx, model-not-found, connection, unknown) fall through — the next provider
     * may succeed. Only request-level failures re-throw: a malformed request fails on every provider, so
     * trying another wastes a call.
     *
     * <p>Request-level = {@link InvalidRequestException} (which {@code ContentFilteredException} extends),
     * OR a raw {@link HttpException} carrying a request-level 4xx status. The {@code HttpException} arm
     * defends against any provider whose {@link dev.langchain4j.model.chat.ChatModel} does not route
     * errors through LangChain4j's {@code ExceptionMapper} (the first-party providers do, so their 4xx
     * already arrive as typed exceptions). It mirrors that mapping: every 4xx is request-level except the
     * codes the mapper remaps to provider-level/retryable types — 401/403 (auth), 404 (model not found),
     * 408 (timeout), 429 (rate limit). 5xx and non-HTTP errors (connection, unknown) fall through.
     */
    public boolean shouldFallback(Throwable error) {
        if (error instanceof InvalidRequestException) {
            return false;
        }
        if (error instanceof HttpException http && isRequestLevelStatus(http.statusCode())) {
            return false;
        }
        return true;
    }

    /** Mirrors LangChain4j's {@code ExceptionMapper}: a 4xx is request-level unless it remaps to a
     *  provider-level/retryable type (401/403 auth, 404 model-not-found, 408 timeout, 429 rate limit). */
    private static boolean isRequestLevelStatus(int status) {
        return status >= 400 && status < 500
                && status != 401 && status != 403 && status != 404 && status != 408 && status != 429;
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
