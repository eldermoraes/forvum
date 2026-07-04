package ai.forvum.engine.memory;

import ai.forvum.core.MemoryHit;
import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.MemoryQuery;
import ai.forvum.core.ModelRef;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.context.CurrentIdentity;
import ai.forvum.engine.memoryquery.EmbeddingSelector;
import ai.forvum.engine.memoryquery.SemanticMemoryStore;
import ai.forvum.engine.memoryquery.VectorCodec;
import ai.forvum.engine.security.OutputFilteredException;
import ai.forvum.engine.security.OutputGuardChain;
import ai.forvum.sdk.HookLayer;
import ai.forvum.sdk.MemoryAccess;
import ai.forvum.sdk.OutputContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * The engine implementation of the #193 {@link MemoryAccess} seam — the backend for the model-callable
 * {@code memory.save} / {@code memory.recall} tool ({@code forvum-tools-memory}). It layers a deliberate,
 * mid-turn write/read surface on the EXISTING #175 machinery, introducing no second index and no parallel
 * schema:
 * <ul>
 *   <li><b>save</b> mirrors {@code MemoryWriter.persistFact} — run the value through the DR-6a
 *       pre-memory-write {@link OutputGuardChain} (redact secrets/PII; a Blocked value is not stored),
 *       embed it, and upsert it into {@code semantic_memory} via the same {@link SemanticMemoryStore#upsertFact}
 *       the off-turn Write phase uses (always the LOCAL store — the {@code MemoryProvider} SPI is
 *       retrieve-only, so there is no external write path).</li>
 *   <li><b>recall</b> reads the LOCAL {@link LocalMemoryProvider} directly — deliberately NOT the
 *       {@code MemorySelector}, which would prefer an active external provider (e.g. Qdrant) and break the
 *       tool's save→recall round-trip, since save can only write local. The explicit memory tool is thus a
 *       local-store surface (an external provider stays the out-of-band auto-retrieval index, #175); a
 *       provider failure degrades to an empty list rather than failing the turn.</li>
 * </ul>
 *
 * <p>The tool runs inside a turn, on the turn's virtual thread, so {@code CURRENT_AGENT} /
 * {@code CURRENT_IDENTITY_ID} are bound: every save and recall is confined to the caller's identity/agent
 * and cannot cross a tenant boundary. Unlike the off-turn {@code MemoryWriter}, this is synchronous — a
 * deliberate save is on the turn's critical path by design (the model asked for it), so the embedding-model
 * call blocks here rather than being deferred.
 */
@ApplicationScoped
public class EngineMemoryAccess implements MemoryAccess {

    private static final Logger LOG = Logger.getLogger(EngineMemoryAccess.class);

    /** Provenance stamped on a fact written by the explicit tool (vs {@code turn:<id>} for the auto path). */
    private static final String SOURCE = "tool:memory.save";

    /**
     * The recall {@link MemoryQuery} needs a non-blank session id, which the provider uses ONLY to exclude
     * the current session's episodes. A deliberate recall wants every relevant episode, and there is no
     * session ScopedValue on the tool path, so a synthetic id matching no real session is the correct value.
     */
    private static final String RECALL_SESSION = "tool:memory.recall";

    @Inject
    SemanticMemoryStore semanticStore;

    @Inject
    EmbeddingSelector embeddings;

    @Inject
    OutputGuardChain outputGuards;

    @Inject
    LocalMemoryProvider localMemory;

    @ConfigProperty(name = "forvum.memory.embedding-model", defaultValue = "ollama:nomic-embed-text")
    String embeddingModel;

    @Override
    public boolean save(String key, String value) {
        AgentId agentId = CurrentAgent.CURRENT_AGENT.get();
        String identityId = CurrentIdentity.currentIdentityId();
        OutputContext ctx = new OutputContext(HookLayer.PRE_MEMORY_WRITE, agentId, CurrentAgent.currentTurnOrNull());
        String stored;
        try {
            // DR-6a section 9 point 2c: redact secrets/PII BEFORE the value is durably stored, so it can
            // never be re-retrieved into a later prompt. A Blocked value throws -> report not-stored to the
            // model; the turn continues normally.
            stored = outputGuards.enforce(ctx, value);
        } catch (OutputFilteredException blocked) {
            LOG.debugf("memory.save value for key '%s' blocked by the pre-memory-write guard; not stored.", key);
            return false;
        }
        // Two deliberate divergences from the best-effort MemoryWriter (documented, not oversights): (1) an
        // embedding-model failure here PROPAGATES — surfaced to the model as a tool error, since a deliberate
        // save that silently returns "saved" without indexing (or is swallowed) is worse than an honest
        // failure the model can report; (2) this explicit surface is NOT gated by forvum.memory.write.enabled
        // (which governs only the automatic writer) — belt membership + the MEMORY_WRITE scope are its opt-in.
        byte[] embedding = VectorCodec.encode(
                embeddings.resolve(ModelRef.parse(embeddingModel)).embed(stored).content().vector());
        semanticStore.upsertFact(identityId, agentId.value(), key, stored, SOURCE, embedding);
        return true;
    }

    @Override
    public List<MemoryHit> recall(String query) {
        if (query == null || query.isBlank()) {
            return List.of(); // MemoryQuery rejects blank text; a blank recall means "nothing", not an error.
        }
        AgentId agentId = CurrentAgent.CURRENT_AGENT.get();
        MemoryQuery mq = new MemoryQuery(agentId.value(), RECALL_SESSION, query);
        try {
            // Read the LOCAL provider directly (the store memory.save writes), NOT MemorySelector, so a
            // deliberate save->recall round-trips even when an external provider is the active auto-retrieval
            // path. MemoryPolicy.defaults() (not the persona's policy) is intentional: the explicit tool is
            // governed by belt membership, orthogonal to the agent's auto-retrieval config — it works even
            // for a strategy=NONE agent. Degrade to empty on any provider failure (no leak, no turn abort).
            return localMemory.retrieve(mq, MemoryPolicy.defaults());
        } catch (RuntimeException e) {
            LOG.warnf(e, "memory.recall failed for agent '%s'; returning no hits.", agentId.value());
            return List.of();
        }
    }
}
