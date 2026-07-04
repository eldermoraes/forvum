package ai.forvum.engine.memory;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.MemoryHit;
import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.MemoryQuery;
import ai.forvum.core.MemoryTier;
import ai.forvum.engine.context.CurrentIdentity;
import ai.forvum.engine.persistence.EpisodicMemoryEntity;
import ai.forvum.engine.persistence.SemanticMemoryEntity;
import ai.forvum.engine.persistence.SessionEntity;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Graceful degradation of {@link LocalMemoryProvider} when the configured embedding model cannot be
 * resolved (#175 acceptance: "memory-provider failure degrades safely without leaking or returning
 * unbounded raw rows"). With the embedding model pinned to a non-existent provider, the SEMANTIC tier
 * degrades to empty — the retrieval never throws — while the embedding-free EPISODIC tier still recalls.
 * The config is set via a profile (not a field-poke) because the provider is a CDI proxy.
 */
@QuarkusTest
@TestProfile(LocalMemoryProviderDegradeIT.DegradeProfile.class)
class LocalMemoryProviderDegradeIT {

    private static final String AGENT = "degrade-agent";
    private static final MemoryPolicy HYBRID = MemoryPolicy.defaults();

    @Inject
    LocalMemoryProvider provider;

    @BeforeEach
    void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            EpisodicMemoryEntity.delete("agentId = ?1", AGENT);
            SemanticMemoryEntity.delete("agentId = ?1", AGENT);
            SessionEntity.delete("agentId = ?1", AGENT);
            SessionEntity session = new SessionEntity();
            session.id = "past";
            session.identityId = "u1";
            session.channelId = "test";
            session.agentId = AGENT;
            long now = System.currentTimeMillis();
            session.startedAt = now;
            session.lastSeenAt = now;
            session.persist();
            EpisodicMemoryEntity episode = new EpisodicMemoryEntity();
            episode.agentId = AGENT;
            episode.sessionId = "past";
            episode.eventType = "observation";
            episode.content = "u1 talked about Paris";
            episode.createdAt = now;
            episode.persist();
            // An EMBEDDED semantic fact, so retrieveSemantic actually attempts the query-embed (which then
            // fails on the broken model) — proving the embed-failure degrade path, not just the empty-store
            // skip. Without a fact here the provider would short-circuit before the embed.
            SemanticMemoryEntity fact = new SemanticMemoryEntity();
            fact.identityId = "u1";
            fact.agentId = AGENT;
            fact.key = "user.city";
            fact.value = "Berlin";
            fact.embedding = new byte[] {1, 2, 3, 4};
            fact.createdAt = now;
            fact.updatedAt = now;
            fact.persist();
        });
    }

    @Test
    void aBrokenEmbeddingModelDegradesSemanticToEmptyButKeepsEpisodicRecall() {
        List<MemoryHit> hits = ScopedValue.where(CurrentIdentity.CURRENT_IDENTITY_ID, "u1")
                .call(() -> provider.retrieve(new MemoryQuery(AGENT, "current", "Paris"), HYBRID));

        // No crash, no semantic hits (the query cannot be embedded), and the embedding-free episodic tier
        // still recalls — a broken embedding model does not take down all retrieval.
        assertTrue(hits.stream().noneMatch(h -> h.tier() == MemoryTier.SEMANTIC),
                "a broken embedding model degrades the semantic tier to empty, never throwing");
        assertTrue(hits.stream().anyMatch(h -> h.tier() == MemoryTier.EPISODIC && h.content().contains("Paris")),
                "the embedding-free episodic tier still recalls when embeddings are unavailable");
    }

    public static class DegradeProfile implements QuarkusTestProfile {
        static final Path HOME = createHome();

        private static Path createHome() {
            try {
                return Files.createTempDirectory("forvum-degrade-home");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "forvum.home", HOME.toString(),
                    "forvum.memory.embedding-model", "nonexistent:model");
        }
    }
}
