package ai.forvum.engine.memory;

import ai.forvum.core.MemoryHit;
import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.MemoryQuery;
import ai.forvum.core.MemoryTier;
import ai.forvum.core.ModelRef;
import ai.forvum.engine.context.CurrentIdentity;
import ai.forvum.engine.memoryquery.EmbeddingSelector;
import ai.forvum.engine.memoryquery.EpisodicMemoryStore;
import ai.forvum.engine.memoryquery.EpisodicMemoryStore.RecentEpisode;
import ai.forvum.engine.memoryquery.SemanticMemoryStore;
import ai.forvum.engine.memoryquery.SemanticMemoryStore.EmbeddedFact;
import ai.forvum.engine.memoryquery.VectorCodec;
import ai.forvum.engine.memoryquery.VectorMath;
import ai.forvum.sdk.AbstractMemoryProvider;

import dev.langchain4j.model.embedding.EmbeddingModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The bundled local-first {@link ai.forvum.sdk.MemoryProvider}: it serves the Context-Engineering
 * <em>Select</em> pillar for a normal turn straight out of the M5 SQLite tiers, with NO external service
 * (#175). It reconciles the CLI index of #50 — it reads the very same {@code semantic_memory} rows through
 * {@link SemanticMemoryStore} and embeds through {@link EmbeddingSelector}, so {@code forvum memory
 * search/reindex} and a turn share one index rather than two incompatible ones.
 *
 * <ul>
 *   <li><b>SEMANTIC</b> — embed the query cue and cosine-scan the identity/agent's embedded facts, mapping
 *       the {@code [-1,1]} cosine into the {@code [0,1]} hit score. An unavailable embedding model degrades
 *       this tier to empty (the turn still gets episodic recall) rather than failing.</li>
 *   <li><b>EPISODIC</b> — cross-session recall of the identity's recent episodes, scored by query-term
 *       overlap. The current session is excluded (its events are already in the short-term window).</li>
 *   <li><b>MESSAGES</b> — a no-op: the current session's conversational history is already assembled into
 *       the prompt window; cross-session message recall is a documented deferral (#175).</li>
 * </ul>
 *
 * <p>Every read is confined to the turn's {@link CurrentIdentity#currentIdentityId()} and
 * {@link MemoryQuery#agentId()} (Isolate) — a hit never crosses an identity or agent. The provider is
 * {@link #isActive()} always ({@code true}); the engine's {@code MemorySelector} steps aside for a
 * configured external provider when one is present.
 */
@ApplicationScoped
public class LocalMemoryProvider extends AbstractMemoryProvider {

    /** The stable extension id the {@code MemorySelector} matches to identify the bundled default. */
    public static final String EXTENSION_ID = "memory-local";

    private static final Logger LOG = Logger.getLogger(LocalMemoryProvider.class);

    /** Bound candidate pool of episodes read before keyword ranking — caps the scan on a large log. */
    private static final int EPISODIC_SCAN_LIMIT = 200;

    @Inject
    SemanticMemoryStore semanticStore;

    @Inject
    EpisodicMemoryStore episodicStore;

    @Inject
    EmbeddingSelector embeddings;

    @ConfigProperty(name = "forvum.memory.embedding-model", defaultValue = "ollama:nomic-embed-text")
    String embeddingModel;

    @Override
    public String extensionId() {
        return EXTENSION_ID;
    }

    @Override
    public List<MemoryHit> retrieve(MemoryQuery query, MemoryPolicy policy) {
        String identityId = CurrentIdentity.currentIdentityId();
        List<MemoryHit> hits = new ArrayList<>();
        if (policy.tiers().contains(MemoryTier.SEMANTIC)) {
            hits.addAll(retrieveSemantic(query, identityId));
        }
        if (policy.tiers().contains(MemoryTier.EPISODIC)) {
            hits.addAll(retrieveEpisodic(query, identityId));
        }
        // MESSAGES: no-op — see the class javadoc.
        return MemoryRanking.topHits(hits, policy.minScore(), policy.topK());
    }

    private List<MemoryHit> retrieveSemantic(MemoryQuery query, String identityId) {
        // Read the embedded facts FIRST: if the agent/identity has nothing embedded to compare against, skip
        // the (blocking) query-embed call entirely. This keeps the common empty-memory turn off the embedding
        // model — critical for latency, since a query embed against an unavailable model would otherwise block
        // the turn on a connect timeout (the per-turn latency gate would blow up), and it is a real
        // optimization: no point paying an embed to cosine against zero rows.
        List<EmbeddedFact> facts = semanticStore.embeddedFacts(identityId, query.agentId());
        if (facts.isEmpty()) {
            return List.of();
        }
        float[] queryVector;
        try {
            EmbeddingModel model = embeddings.resolve(ModelRef.parse(embeddingModel));
            queryVector = model.embed(query.text()).content().vector();
        } catch (RuntimeException e) {
            // No embedding model, or an embed failure: degrade the semantic tier to empty (episodic recall
            // still works). A retrieval problem must never fail the turn (the host also swallows, but
            // keeping episodic alive here is the graceful behavior).
            LOG.debugf("Semantic retrieval skipped for agent '%s' (embedding unavailable: %s)",
                    query.agentId(), e.getMessage());
            return List.of();
        }
        List<MemoryHit> hits = new ArrayList<>();
        for (EmbeddedFact fact : facts) {
            float[] factVector = VectorCodec.decode(fact.embedding());
            if (factVector == null || factVector.length != queryVector.length) {
                continue; // a fact embedded by a different model (dimension drift) — skip, don't crash
            }
            double score = MemoryRanking.normalizeCosine(VectorMath.cosine(queryVector, factVector));
            hits.add(new MemoryHit(MemoryTier.SEMANTIC, fact.value(), score, "semantic:" + fact.key()));
        }
        return hits;
    }

    private List<MemoryHit> retrieveEpisodic(MemoryQuery query, String identityId) {
        List<MemoryHit> hits = new ArrayList<>();
        for (RecentEpisode episode : episodicStore.recentEpisodes(
                identityId, query.agentId(), query.sessionId(), EPISODIC_SCAN_LIMIT)) {
            double score = MemoryRanking.keywordScore(query.text(), episode.content());
            hits.add(new MemoryHit(MemoryTier.EPISODIC, episode.content(), score, "episodic:" + episode.id()));
        }
        return hits;
    }
}
