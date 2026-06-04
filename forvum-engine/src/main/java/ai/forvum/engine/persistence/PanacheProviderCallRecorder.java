package ai.forvum.engine.persistence;

import ai.forvum.engine.model.ProviderCall;
import ai.forvum.engine.model.ProviderCallRecorder;

import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

/** Panache-backed {@link ProviderCallRecorder}: maps a {@link ProviderCall} to a row in provider_calls. */
@Singleton
public class PanacheProviderCallRecorder implements ProviderCallRecorder {

    @Override
    @Transactional
    public void record(ProviderCall call) {
        ProviderCallEntity entity = new ProviderCallEntity();
        entity.sessionId = call.sessionId();
        entity.agentId = call.agentId();
        entity.provider = call.provider();
        entity.model = call.model();
        entity.tokensIn = call.tokensIn();
        entity.tokensOut = call.tokensOut();
        entity.costUsd = call.costUsd();
        entity.latencyMs = call.latencyMs();
        entity.isFallback = call.fallback() ? 1 : 0;
        entity.error = call.error();
        entity.createdAt = call.createdAt();
        entity.persist();
    }
}
