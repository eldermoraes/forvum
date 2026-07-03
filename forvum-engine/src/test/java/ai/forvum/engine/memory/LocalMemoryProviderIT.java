package ai.forvum.engine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.MemoryHit;
import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.MemoryQuery;
import ai.forvum.core.MemoryTier;
import ai.forvum.core.ModelRef;
import ai.forvum.core.RetrievalStrategy;
import ai.forvum.engine.context.CurrentIdentity;
import ai.forvum.engine.memoryquery.MemoryQueryService;
import ai.forvum.engine.persistence.EpisodicMemoryEntity;
import ai.forvum.engine.persistence.SemanticMemoryEntity;
import ai.forvum.engine.persistence.SessionEntity;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

/**
 * {@link LocalMemoryProvider} end-to-end over real SQLite (#175): the semantic cosine tier, the episodic
 * keyword tier, strict identity/agent scoping (no cross-tenant leak), tier restriction, and topK. Facts are
 * embedded via the same {@link MemoryQueryService#reindex} path #50 uses (a single reconciled index), with
 * the deterministic {@code fake-embed} model so an exact text match scores 1.0.
 */
@QuarkusTest
@TestProfile(LocalMemoryTestProfile.class)
class LocalMemoryProviderIT {

    private static final ModelRef EMBED = ModelRef.parse("fake-embed:test");
    private static final String AGENT = "localmem-agent";
    private static final MemoryPolicy HYBRID = MemoryPolicy.defaults();

    @Inject
    LocalMemoryProvider provider;

    @Inject
    MemoryQueryService memoryQuery;

    @BeforeEach
    void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            SemanticMemoryEntity.delete("agentId = ?1", AGENT);
            EpisodicMemoryEntity.delete("agentId = ?1", AGENT);
            SessionEntity.delete("agentId = ?1", AGENT);
            session("u1-cur", "u1", AGENT);
            session("u1-past", "u1", AGENT);
            session("u2-cur", "u2", AGENT);
            fact("u1", "fav-color", "blue");
            fact("u1", "fav-fruit", "apple");
            fact("u2", "secret", "u2-only-secret");
            episode("u1-past", "u1 visited Paris last spring", 100);
        });
        // Populate embeddings through the very same store/model path #50's CLI uses (one reconciled index).
        memoryQuery.reindex(EMBED, "u1", AGENT);
        memoryQuery.reindex(EMBED, "u2", AGENT);
    }

    @Test
    void semanticRetrievalRanksTheEmbeddedFactByCosineSimilarity() {
        List<MemoryHit> hits = retrieveAs("u1", "u1-cur", "blue", HYBRID);

        assertFalse(hits.isEmpty(), "an embedded fact matching the query must be retrieved without Qdrant");
        MemoryHit top = hits.get(0);
        assertEquals(MemoryTier.SEMANTIC, top.tier());
        assertEquals("blue", top.content(), "the exact-match fact ranks first");
        assertEquals("semantic:fav-color", top.source(), "provenance names the tier + fact key");
        assertEquals(1.0, top.score(), 1e-6, "an identical fake embedding normalizes to a 1.0 score");
    }

    @Test
    void retrievalIsScopedToTheBoundIdentityAndNeverCrossesTenants() {
        List<MemoryHit> u1 = retrieveAs("u1", "u1-cur", "secret", HYBRID);
        assertTrue(u1.stream().noneMatch(h -> h.content().contains("u2-only")),
                "u1 must never retrieve u2's semantic fact");

        List<MemoryHit> u2 = retrieveAs("u2", "u2-cur", "blue", HYBRID);
        assertTrue(u2.stream().noneMatch(h -> "blue".equals(h.content())),
                "u2 must never retrieve u1's semantic fact");
    }

    @Test
    void episodicRetrievalRecallsAcrossPriorSessionsByKeyword() {
        List<MemoryHit> hits = retrieveAs("u1", "u1-cur", "Paris", HYBRID);
        assertTrue(hits.stream().anyMatch(h -> h.tier() == MemoryTier.EPISODIC
                        && h.content().contains("Paris")),
                "a prior-session episode mentioning Paris must recall cross-session");
    }

    @Test
    void tiersRestrictWhichTiersAreDrawnFrom() {
        MemoryPolicy episodicOnly = new MemoryPolicy(
                RetrievalStrategy.HYBRID, EnumSet.of(MemoryTier.EPISODIC), 8, 0.0, 8000);
        List<MemoryHit> hits = retrieveAs("u1", "u1-cur", "blue", episodicOnly);
        assertTrue(hits.stream().allMatch(h -> h.tier() == MemoryTier.EPISODIC),
                "with only EPISODIC in the policy, no SEMANTIC hit may be returned");
    }

    @Test
    void topKCapsTheReturnedCount() {
        MemoryPolicy topOne = new MemoryPolicy(
                RetrievalStrategy.HYBRID, EnumSet.allOf(MemoryTier.class), 1, 0.0, 8000);
        assertEquals(1, retrieveAs("u1", "u1-cur", "blue", topOne).size(), "topK=1 returns at most one hit");
    }

    private List<MemoryHit> retrieveAs(String identity, String session, String text, MemoryPolicy policy) {
        return ScopedValue.where(CurrentIdentity.CURRENT_IDENTITY_ID, identity)
                .call(() -> provider.retrieve(new MemoryQuery(AGENT, session, text), policy));
    }

    private static void session(String id, String identityId, String agentId) {
        SessionEntity session = new SessionEntity();
        session.id = id;
        session.identityId = identityId;
        session.channelId = "test";
        session.agentId = agentId;
        long now = System.currentTimeMillis();
        session.startedAt = now;
        session.lastSeenAt = now;
        session.persist();
    }

    private static void fact(String identityId, String key, String value) {
        SemanticMemoryEntity row = new SemanticMemoryEntity();
        row.identityId = identityId;
        row.agentId = AGENT;
        row.key = key;
        row.value = value;
        long now = System.currentTimeMillis();
        row.createdAt = now;
        row.updatedAt = now;
        row.persist();
    }

    private static void episode(String sessionId, String content, long createdAt) {
        EpisodicMemoryEntity episode = new EpisodicMemoryEntity();
        episode.agentId = AGENT;
        episode.sessionId = sessionId;
        episode.eventType = "observation";
        episode.content = content;
        episode.createdAt = createdAt;
        episode.persist();
    }
}
