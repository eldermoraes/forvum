package ai.forvum.sdk;

import ai.forvum.core.MemoryHit;

import java.util.List;

/**
 * The engine-backed access seam the explicit memory tool (#193, {@code forvum-tools-memory}) drives to
 * deliberately persist or retrieve a durable fact mid-turn — the model-callable {@code memory.save} /
 * {@code memory.recall} surface layered on #175's local {@code MemoryProvider} + {@code MemoryPolicy}
 * contract.
 *
 * <p>This is a <strong>Resolution-B seam</strong> (the {@code ChannelTurnDriver}/{@link TaskExecutor}
 * pattern), NOT a sealed provider: the single implementation lives in {@code forvum-engine} — where the
 * memory machinery already sits ({@code SemanticMemoryStore}, the DR-6a pre-memory-write
 * {@code OutputGuardChain}, {@code MemorySelector}, the identity/agent scope bindings) — and plugins do NOT
 * implement it. It is promoted to {@code forvum-sdk} only so a Layer-3 tool module (which the enforcer bars
 * from depending on {@code forvum-engine}) can inject the contract and let ArC resolve it to the engine
 * bean. A plain (non-sealed) interface: the engine is the sole implementor, so there is no closed
 * implementor set to seal.
 *
 * <p>Reuses #175's storage/provider contract exactly — no second index, no parallel schema. Both calls are
 * scoped to the current turn's identity/agent (read from the engine's scope bindings), so neither can cross
 * a tenant boundary. {@code forvum-sdk} is Quarkus-free; the contract takes/returns only Layer-0 types
 * ({@link MemoryHit}) + JDK types, so it is reflection-free and native-safe.
 */
public interface MemoryAccess {

    /**
     * Persist {@code value} under {@code key} as a durable fact for the current identity/agent, routed
     * through the pre-memory-write filter first. Returns {@code true} when stored (possibly with secrets
     * redacted), {@code false} when the filter blocked the value entirely (nothing is stored).
     */
    boolean save(String key, String value);

    /**
     * Retrieve durable facts relevant to {@code query} for the current identity/agent, ranked and bounded
     * by the default retrieval policy. Never crosses a tenant boundary; returns an empty list when memory
     * is unavailable (the #175 degrade-safely posture).
     */
    List<MemoryHit> recall(String query);
}
