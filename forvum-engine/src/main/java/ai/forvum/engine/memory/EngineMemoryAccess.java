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
import ai.forvum.engine.routing.MemorySelector;
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
 *   <li><b>save</b> mirrors {@code MemoryWriter.persistFact} exactly — run the value through the DR-6a
 *       pre-memory-write {@link OutputGuardChain} (redact secrets/PII; a Blocked value is not stored),
 *       embed it, and upsert it into {@code semantic_memory} via the same {@link SemanticMemoryStore#upsertFact}
 *       the off-turn Write phase uses.</li>
 *   <li><b>recall</b> delegates to {@link MemorySelector#retrieve} — the same Select path the automatic
 *       pre-prompt retrieval uses (external provider if active, else the local default) — so a saved fact
 *       is retrieved through the installed provider's ranking, and a provider failure degrades to an empty
 *       list rather than failing the turn.</li>
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
    MemorySelector memorySelector;

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
        byte[] embedding = VectorCodec.encode(
                embeddings.resolve(ModelRef.parse(embeddingModel)).embed(stored).content().vector());
        semanticStore.upsertFact(identityId, agentId.value(), key, stored, SOURCE, embedding);
        return true;
    }

    @Override
    public List<MemoryHit> recall(String query) {
        AgentId agentId = CurrentAgent.CURRENT_AGENT.get();
        MemoryQuery mq = new MemoryQuery(agentId.value(), RECALL_SESSION, query);
        return memorySelector.retrieve(mq, MemoryPolicy.defaults());
    }
}
