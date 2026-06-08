package ai.forvum.provider.memory.qdrant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.MemoryHit;
import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.MemoryQuery;
import ai.forvum.core.MemoryTier;
import ai.forvum.core.RetrievalStrategy;

import ai.forvum.provider.memory.qdrant.dto.QdrantFieldCondition;
import ai.forvum.provider.memory.qdrant.dto.QdrantPoint;
import ai.forvum.provider.memory.qdrant.dto.QdrantScrollRequest;
import ai.forvum.provider.memory.qdrant.dto.QdrantSearchRequest;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Pure request-building and response-mapping invariants for the Qdrant reference provider (P2-5). */
class QdrantRetrievalTest {

    private static final MemoryQuery QUERY = new MemoryQuery("agent-1", "sess-7", "what did we decide");

    // ---- request building ----------------------------------------------------------------------

    @Test
    void searchRequestCarriesEmbeddingTopKAndScopeFilter() {
        MemoryPolicy policy = new MemoryPolicy(
                RetrievalStrategy.VECTOR, EnumSet.of(MemoryTier.SEMANTIC), 5, 0.3, 0);

        QdrantSearchRequest req = QdrantRetrieval.searchRequest(QUERY, policy, MemoryTier.SEMANTIC);

        assertEquals(ReferenceEmbedding.DIMENSION, req.vector().size());
        assertEquals(ReferenceEmbedding.embed(QUERY.text()), req.vector(), "embedding is deterministic");
        assertEquals(5, req.limit(), "limit is the policy topK");
        assertEquals(0.3, req.scoreThreshold(), "a positive minScore becomes the Qdrant score_threshold");
        assertTrue(req.withPayload());
        assertScopeFilter(req.filter().must(), "semantic");
    }

    @Test
    void searchRequestOmitsScoreThresholdWhenMinScoreIsZero() {
        MemoryPolicy policy = new MemoryPolicy(
                RetrievalStrategy.VECTOR, EnumSet.of(MemoryTier.SEMANTIC), 5, 0.0, 0);

        QdrantSearchRequest req = QdrantRetrieval.searchRequest(QUERY, policy, MemoryTier.SEMANTIC);

        assertNull(req.scoreThreshold(), "minScore 0 means no Qdrant threshold");
    }

    @Test
    void scrollRequestCarriesTopKAndScopeFilterAndNoVector() {
        MemoryPolicy policy = new MemoryPolicy(
                RetrievalStrategy.METADATA, EnumSet.of(MemoryTier.EPISODIC), 9, 0.0, 0);

        QdrantScrollRequest req = QdrantRetrieval.scrollRequest(QUERY, policy, MemoryTier.EPISODIC);

        assertEquals(9, req.limit());
        assertTrue(req.withPayload());
        assertScopeFilter(req.filter().must(), "episodic");
    }

    @Test
    void metadataStrategyIsTheScrollPath() {
        assertTrue(QdrantRetrieval.isMetadataStrategy(new MemoryPolicy(
                RetrievalStrategy.METADATA, EnumSet.of(MemoryTier.SEMANTIC), 1, 0.0, 0)));
        assertFalse(QdrantRetrieval.isMetadataStrategy(new MemoryPolicy(
                RetrievalStrategy.VECTOR, EnumSet.of(MemoryTier.SEMANTIC), 1, 0.0, 0)));
    }

    // ---- response mapping ----------------------------------------------------------------------

    @Test
    void pointMapsToHitReadingContentAndSourceFromPayload() {
        QdrantPoint p = new QdrantPoint("p1", 0.81,
                Map.of("content", "we shipped v0.1", "source", "msg#5"));

        MemoryHit hit = QdrantRetrieval.toHit(p, MemoryTier.SEMANTIC, Double.NaN);

        assertEquals(MemoryTier.SEMANTIC, hit.tier());
        assertEquals("we shipped v0.1", hit.content());
        assertEquals(0.81, hit.score());
        assertEquals("msg#5", hit.source());
    }

    @Test
    void pointWithoutSourceFallsBackToTheId() {
        QdrantPoint p = new QdrantPoint(42, 0.5, Map.of("content", "x"));
        assertEquals("42", QdrantRetrieval.toHit(p, MemoryTier.MESSAGES, Double.NaN).source());
    }

    @Test
    void scrollPointUsesTheDefaultScore() {
        QdrantPoint p = new QdrantPoint("p1", null, Map.of("content", "metadata hit"));
        MemoryHit hit = QdrantRetrieval.toHit(p, MemoryTier.EPISODIC, QdrantRetrieval.METADATA_SCORE);
        assertEquals(1.0, hit.score(), "an unscored scroll point takes the default metadata score");
    }

    @Test
    void outOfRangeQdrantScoreIsClampedIntoUnitRange() {
        // Qdrant cosine scores can be negative; the MemoryHit contract requires [0,1].
        assertEquals(0.0, QdrantRetrieval.toHit(
                new QdrantPoint("a", -0.4, Map.of("content", "c")), MemoryTier.SEMANTIC, Double.NaN).score());
        assertEquals(1.0, QdrantRetrieval.toHit(
                new QdrantPoint("b", 1.7, Map.of("content", "c")), MemoryTier.SEMANTIC, Double.NaN).score());
    }

    @Test
    void pointWithNullPayloadIsSkipped() {
        assertNull(QdrantRetrieval.toHit(new QdrantPoint("a", 0.5, null), MemoryTier.SEMANTIC, Double.NaN));
    }

    // ---- merge: topK, minScore, ordering -------------------------------------------------------

    @Test
    void mergeFiltersByMinScoreSortsAndCapsAtTopK() {
        MemoryPolicy policy = new MemoryPolicy(
                RetrievalStrategy.HYBRID, EnumSet.allOf(MemoryTier.class), 2, 0.5, 0);
        List<MemoryHit> hits = List.of(
                new MemoryHit(MemoryTier.SEMANTIC, "low", 0.40, "a"),   // below minScore -> dropped
                new MemoryHit(MemoryTier.SEMANTIC, "mid", 0.60, "b"),
                new MemoryHit(MemoryTier.EPISODIC, "high", 0.90, "c"),
                new MemoryHit(MemoryTier.MESSAGES, "top", 0.95, "d"));

        List<MemoryHit> merged = QdrantRetrieval.merge(hits, policy);

        assertEquals(2, merged.size(), "capped at topK");
        assertEquals("top", merged.get(0).content(), "highest score first");
        assertEquals("high", merged.get(1).content());
    }

    @Test
    void mergeKeepsHitsExactlyAtMinScore() {
        MemoryPolicy policy = new MemoryPolicy(
                RetrievalStrategy.VECTOR, EnumSet.of(MemoryTier.SEMANTIC), 10, 0.5, 0);
        List<MemoryHit> merged = QdrantRetrieval.merge(
                List.of(new MemoryHit(MemoryTier.SEMANTIC, "boundary", 0.5, "a")), policy);
        assertEquals(1, merged.size(), "minScore is inclusive");
    }

    @Test
    void mergeSkipsNullsAndReturnsEmptyWhenNothingClears() {
        MemoryPolicy policy = new MemoryPolicy(
                RetrievalStrategy.VECTOR, EnumSet.of(MemoryTier.SEMANTIC), 10, 0.9, 0);
        List<MemoryHit> merged = QdrantRetrieval.merge(
                java.util.Arrays.asList(null, new MemoryHit(MemoryTier.SEMANTIC, "x", 0.1, "a")), policy);
        assertTrue(merged.isEmpty());
    }

    private static void assertScopeFilter(List<QdrantFieldCondition> must, String tierValue) {
        assertEquals(3, must.size(), "agent + session + tier conditions");
        assertEquals("agent_id", must.get(0).key());
        assertEquals("agent-1", must.get(0).match().value());
        assertEquals("session_id", must.get(1).key());
        assertEquals("sess-7", must.get(1).match().value());
        assertEquals("tier", must.get(2).key());
        assertEquals(tierValue, must.get(2).match().value());
    }
}
