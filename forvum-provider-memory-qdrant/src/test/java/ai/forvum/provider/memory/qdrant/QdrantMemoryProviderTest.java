package ai.forvum.provider.memory.qdrant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.MemoryHit;
import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.MemoryQuery;
import ai.forvum.core.MemoryTier;
import ai.forvum.core.RetrievalStrategy;

import ai.forvum.provider.memory.qdrant.dto.QdrantPoint;
import ai.forvum.provider.memory.qdrant.dto.QdrantScrollResponse;
import ai.forvum.provider.memory.qdrant.dto.QdrantSearchResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * {@code QdrantMemoryProvider.retrieve} end-to-end against a {@link FakeQdrantApi}: maps Qdrant results to
 * {@link MemoryHit}s honoring topK / minScore / tiers, takes the embedding-free scroll path for METADATA,
 * and is INERT when unconfigured or on a backend failure. Plain unit tests — the provider is constructed
 * directly (no Quarkus boot), the config bound to a temp {@code qdrant.json}.
 */
class QdrantMemoryProviderTest {

    private static final MemoryQuery QUERY = new MemoryQuery("agent-1", "sess-7", "what did we decide");

    private static QdrantMemoryProvider provider(Path configFile, FakeQdrantApi api) {
        return new QdrantMemoryProvider(new QdrantConfig(configFile), api);
    }

    private static Path activeConfig(Path dir) throws Exception {
        Path file = dir.resolve("qdrant.json");
        Files.writeString(file,
                "{ \"url\": \"http://qd:6333\", \"apiKey\": \"k\", \"collection\": \"mem\" }");
        return file;
    }

    @Test
    void extensionIdMatchesThePluginManifest() {
        assertEquals("memory-qdrant", provider(Path.of("nope.json"), new FakeQdrantApi()).extensionId());
    }

    @Test
    void unconfiguredProviderIsInertAndIssuesNoCall(@TempDir Path dir) {
        FakeQdrantApi api = new FakeQdrantApi();
        QdrantMemoryProvider p = provider(dir.resolve("absent.json"), api);

        List<MemoryHit> hits = p.retrieve(QUERY, MemoryPolicy.defaults());

        assertTrue(hits.isEmpty(), "no config => no hits");
        assertTrue(api.searches.isEmpty() && api.scrolls.isEmpty(), "no config => no backend call");
    }

    @Test
    void strategyNoneReturnsEmptyWithoutCallingTheBackend(@TempDir Path dir) throws Exception {
        FakeQdrantApi api = new FakeQdrantApi();
        QdrantMemoryProvider p = provider(activeConfig(dir), api);

        MemoryPolicy none = new MemoryPolicy(
                RetrievalStrategy.NONE, EnumSet.noneOf(MemoryTier.class), 8, 0.0, 0);
        List<MemoryHit> hits = p.retrieve(QUERY, none);

        assertTrue(hits.isEmpty());
        assertTrue(api.searches.isEmpty() && api.scrolls.isEmpty(), "NONE issues no call");
    }

    @Test
    void vectorSearchMapsResultsToHitsAndPassesAuthAndCollection(@TempDir Path dir) throws Exception {
        FakeQdrantApi api = new FakeQdrantApi();
        api.searchResponder = req -> new QdrantSearchResponse(List.of(
                new QdrantPoint("p1", 0.9, Map.of("content", "fact A", "source", "sem#1")),
                new QdrantPoint("p2", 0.7, Map.of("content", "fact B"))), "ok");
        QdrantMemoryProvider p = provider(activeConfig(dir), api);

        MemoryPolicy policy = new MemoryPolicy(
                RetrievalStrategy.VECTOR, EnumSet.of(MemoryTier.SEMANTIC), 8, 0.0, 0);
        List<MemoryHit> hits = p.retrieve(QUERY, policy);

        assertEquals(2, hits.size());
        assertEquals("fact A", hits.get(0).content(), "highest score first");
        assertEquals("sem#1", hits.get(0).source());
        assertEquals(MemoryTier.SEMANTIC, hits.get(0).tier());
        assertEquals("p2", hits.get(1).source(), "missing source falls back to the point id");

        assertEquals(1, api.searches.size(), "one tier => one search");
        assertTrue(api.scrolls.isEmpty(), "VECTOR does not scroll");
        assertEquals("http://qd:6333", api.baseUrls.get(0));
        assertEquals("k", api.apiKeys.get(0));
        assertEquals("mem", api.collections.get(0));
    }

    @Test
    void metadataStrategyUsesScrollNotSearch(@TempDir Path dir) throws Exception {
        FakeQdrantApi api = new FakeQdrantApi();
        api.scrollResponder = req -> new QdrantScrollResponse(new QdrantScrollResponse.Page(List.of(
                new QdrantPoint("e1", null, Map.of("content", "an episode")))), "ok");
        QdrantMemoryProvider p = provider(activeConfig(dir), api);

        MemoryPolicy policy = new MemoryPolicy(
                RetrievalStrategy.METADATA, EnumSet.of(MemoryTier.EPISODIC), 8, 0.0, 0);
        List<MemoryHit> hits = p.retrieve(QUERY, policy);

        assertEquals(1, hits.size());
        assertEquals("an episode", hits.get(0).content());
        assertEquals(1.0, hits.get(0).score(), "scroll hits take the metadata score");
        assertTrue(api.searches.isEmpty(), "METADATA is embedding-free: no vector search");
        assertEquals(1, api.scrolls.size());
    }

    @Test
    void honorsMinScoreAndTopKAcrossTiers(@TempDir Path dir) throws Exception {
        FakeQdrantApi api = new FakeQdrantApi();
        // every tier returns the same three points; merge must filter by minScore and cap at topK
        api.searchResponder = req -> new QdrantSearchResponse(List.of(
                new QdrantPoint("p1", 0.95, Map.of("content", "top")),
                new QdrantPoint("p2", 0.55, Map.of("content", "mid")),
                new QdrantPoint("p3", 0.20, Map.of("content", "low"))), "ok");
        QdrantMemoryProvider p = provider(activeConfig(dir), api);

        MemoryPolicy policy = new MemoryPolicy(
                RetrievalStrategy.HYBRID, EnumSet.of(MemoryTier.SEMANTIC, MemoryTier.EPISODIC), 2, 0.5, 0);
        List<MemoryHit> hits = p.retrieve(QUERY, policy);

        assertEquals(2, hits.size(), "capped at topK=2 across both tiers");
        assertTrue(hits.stream().allMatch(h -> h.score() >= 0.5), "all hits clear minScore");
        assertEquals("top", hits.get(0).content());
        assertEquals(2, api.searches.size(), "one search per selected tier");
    }

    @Test
    void everyReturnedHitComesFromASelectedTier(@TempDir Path dir) throws Exception {
        FakeQdrantApi api = new FakeQdrantApi();
        api.searchResponder = req -> new QdrantSearchResponse(List.of(
                new QdrantPoint("x", 0.8, Map.of("content", "c"))), "ok");
        QdrantMemoryProvider p = provider(activeConfig(dir), api);

        MemoryPolicy policy = new MemoryPolicy(
                RetrievalStrategy.VECTOR, EnumSet.of(MemoryTier.MESSAGES), 8, 0.0, 0);
        List<MemoryHit> hits = p.retrieve(QUERY, policy);

        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().allMatch(h -> policy.tiers().contains(h.tier())),
                "a hit's tier is one the policy selected");
    }

    @Test
    void backendFailureDegradesToNoHitsAndDoesNotPropagate(@TempDir Path dir) throws Exception {
        FakeQdrantApi api = new FakeQdrantApi();
        api.throwOnSearch = true;
        QdrantMemoryProvider p = provider(activeConfig(dir), api);

        MemoryPolicy policy = new MemoryPolicy(
                RetrievalStrategy.VECTOR, EnumSet.of(MemoryTier.SEMANTIC), 8, 0.0, 0);
        List<MemoryHit> hits = p.retrieve(QUERY, policy);

        assertTrue(hits.isEmpty(), "a backend failure must not crash the turn");
    }

    @Test
    void nullEnvelopeFromBackendYieldsNoHits(@TempDir Path dir) throws Exception {
        FakeQdrantApi api = new FakeQdrantApi();
        api.searchResponder = req -> new QdrantSearchResponse(null, "error");  // result == null
        QdrantMemoryProvider p = provider(activeConfig(dir), api);

        MemoryPolicy policy = new MemoryPolicy(
                RetrievalStrategy.VECTOR, EnumSet.of(MemoryTier.SEMANTIC), 8, 0.0, 0);
        assertTrue(p.retrieve(QUERY, policy).isEmpty());
    }
}
