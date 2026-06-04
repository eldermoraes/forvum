package ai.forvum.engine.model;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

/** Builds {@link ProviderCall} ledger rows from attempt outcomes. Shared by both fallback decorators. */
final class ProviderCalls {

    private ProviderCalls() {
    }

    static ProviderCall success(String sessionId, String agentId, FallbackLink link, boolean fallback,
            ChatResponse response, long latencyMs) {
        return new ProviderCall(sessionId, agentId, link.ref().provider(), link.ref().model(),
                tokensIn(response), tokensOut(response), null, latencyMs, fallback, null,
                System.currentTimeMillis());
    }

    static ProviderCall failure(String sessionId, String agentId, FallbackLink link, boolean fallback,
            Throwable error, long latencyMs) {
        return new ProviderCall(sessionId, agentId, link.ref().provider(), link.ref().model(),
                0L, 0L, null, latencyMs, fallback, error.getClass().getName(),
                System.currentTimeMillis());
    }

    private static long tokensIn(ChatResponse response) {
        TokenUsage usage = response.tokenUsage();
        return usage != null && usage.inputTokenCount() != null ? usage.inputTokenCount() : 0L;
    }

    private static long tokensOut(ChatResponse response) {
        TokenUsage usage = response.tokenUsage();
        return usage != null && usage.outputTokenCount() != null ? usage.outputTokenCount() : 0L;
    }
}
