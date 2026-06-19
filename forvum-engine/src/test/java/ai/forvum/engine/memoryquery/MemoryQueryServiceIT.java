package ai.forvum.engine.memoryquery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ModelRef;
import ai.forvum.engine.persistence.SemanticMemoryEntity;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Boots Quarkus against a fresh temp SQLite DB and exercises {@link MemoryQueryService} end-to-end (P3-2,
 * #50): the read-only SQL query path (with the guard), the linear vector search (over a deterministic fake
 * embedding model), and the reindex backfill. Runs via Surefire — {@code forvum-engine} is a headless
 * library (CLAUDE.md §4). Embeddings come from {@link FakeEmbeddingModelProvider} ({@code fake-embed}),
 * so no live Ollama is needed and the cosine ranking is deterministic.
 */
@QuarkusTest
@TestProfile(MemoryQueryTestHomeProfile.class)
class MemoryQueryServiceIT {

    private static final ModelRef EMBED_REF = ModelRef.parse("fake-embed:test");
    private static final String IDENTITY = "default";
    private static final String AGENT = "memq-agent";

    @Inject
    MemoryQueryService service;

    @BeforeEach
    void seed() {
        // Reset just this agent's rows so each test is independent (shared @TestProfile DB, §14).
        QuarkusTransaction.requiringNew().run(() -> {
            SemanticMemoryEntity.delete("agentId = ?1", AGENT);
            persist("favorite-color", "blue");
            persist("favorite-fruit", "apple");
            persist("home-city", "Lisbon");
        });
    }

    private static void persist(String key, String value) {
        SemanticMemoryEntity row = new SemanticMemoryEntity();
        row.identityId = IDENTITY;
        row.agentId = AGENT;
        row.key = key;
        row.value = value;
        long now = System.currentTimeMillis();
        row.createdAt = now;
        row.updatedAt = now;
        row.persist();
    }

    @Test
    void queryReturnsRowsForAReadOnlySelect() {
        QueryResult result = service.query(
                "SELECT key, value FROM semantic_memory WHERE agent_id = '" + AGENT + "' ORDER BY key", 100);
        assertEquals(List.of("key", "value"), result.columns());
        assertEquals(3, result.rows().size());
        assertFalse(result.truncated());
        assertTrue(result.rows().stream().anyMatch(r -> r.equals(List.of("favorite-color", "blue"))));
    }

    @Test
    void queryHonorsTheRowLimitAndReportsTruncation() {
        QueryResult result = service.query(
                "SELECT key FROM semantic_memory WHERE agent_id = '" + AGENT + "'", 2);
        assertEquals(2, result.rows().size());
        assertTrue(result.truncated(), "fetching 2 of 3 rows must report truncation");
    }

    @Test
    void queryRejectsANonSelect() {
        assertThrows(IllegalArgumentException.class,
                () -> service.query("DELETE FROM semantic_memory WHERE agent_id = '" + AGENT + "'", 100));
    }

    @Test
    void searchOnUnindexedMemoryReturnsNothing() {
        // No reindex yet → no embeddings → search finds nothing (the rows are invisible until embedded).
        List<SearchHit> hits = service.search(EMBED_REF, "what is my favorite color", IDENTITY, AGENT, 5);
        assertTrue(hits.isEmpty(), "unindexed memory must not surface in search; got: " + hits);
    }

    @Test
    void reindexThenSearchRanksTheMostSimilarFactFirst() {
        int embedded = service.reindex(EMBED_REF, IDENTITY, AGENT);
        assertEquals(3, embedded, "reindex must embed all three rows");

        // A second reindex is a no-op (all rows already have an embedding).
        assertEquals(0, service.reindex(EMBED_REF, IDENTITY, AGENT));

        // The query text equals a stored value, so the deterministic fake embedding makes that row the
        // exact (cosine == 1.0) nearest neighbor and it must rank first.
        List<SearchHit> hits = service.search(EMBED_REF, "blue", IDENTITY, AGENT, 3);
        assertEquals(3, hits.size());
        assertEquals("favorite-color", hits.get(0).key(), "the exact-match value must rank first");
        assertEquals("blue", hits.get(0).value());
        assertEquals(1.0, hits.get(0).score(), 1e-6, "an identical embedding has cosine 1.0");
        // Scores are non-increasing (most-similar first).
        for (int i = 1; i < hits.size(); i++) {
            assertTrue(hits.get(i).score() <= hits.get(i - 1).score() + 1e-9,
                    "search must return hits in non-increasing score order");
        }
    }

    @Test
    void searchHonorsTopK() {
        service.reindex(EMBED_REF, IDENTITY, AGENT);
        List<SearchHit> hits = service.search(EMBED_REF, "apple", IDENTITY, AGENT, 1);
        assertEquals(1, hits.size(), "top-K must cap the result count");
        assertEquals("favorite-fruit", hits.get(0).key());
    }
}
