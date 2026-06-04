package ai.forvum.engine.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One {@code provider_calls} ledger row produced per attempted LLM call (ULTRAPLAN section 4.2 / 5.4).
 * {@code fallback} is true for every link past the first; {@code error} holds the failing exception's
 * FQCN (null on success). Layer-2 DTO — carries the real Quarkus {@code @RegisterForReflection} (the
 * forvum-sdk re-export is inert until its build step ships).
 */
@RegisterForReflection
public record ProviderCall(
        String sessionId,
        String agentId,
        String provider,
        String model,
        long tokensIn,
        long tokensOut,
        Double costUsd,
        long latencyMs,
        boolean fallback,
        String error,
        long createdAt) {
}
