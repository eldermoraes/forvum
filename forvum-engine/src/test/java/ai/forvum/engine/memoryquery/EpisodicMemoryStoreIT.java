package ai.forvum.engine.memoryquery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.engine.memoryquery.EpisodicMemoryStore.RecentEpisode;
import ai.forvum.engine.persistence.EpisodicMemoryEntity;
import ai.forvum.engine.persistence.SessionEntity;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * {@link EpisodicMemoryStore} reads the episodic tier for the local {@code MemoryProvider} (#175). Episodic
 * rows carry no {@code identity_id} of their own (V1 schema), so identity scoping is via a join to
 * {@code sessions.identity_id} — this IT proves the join confines recall to one identity/agent, orders by
 * recency, honors the row cap, and can exclude the current session. Real SQLite via the seeded temp home.
 */
@QuarkusTest
@TestProfile(MemoryQueryTestHomeProfile.class)
class EpisodicMemoryStoreIT {

    private static final String AGENT = "epi-agent";

    @Inject
    EpisodicMemoryStore store;

    @BeforeEach
    void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            // Reset just this agent's rows so each test (and re-run) is independent (shared @TestProfile DB).
            EpisodicMemoryEntity.delete("agentId = ?1", AGENT);
            SessionEntity.delete("agentId = ?1", AGENT);
            // Two Alice sessions and one Bob session under the SAME agent.
            session("s-alice-1", "alice", AGENT);
            session("s-alice-2", "alice", AGENT);
            session("s-bob-1", "bob", AGENT);
            episode(AGENT, "s-alice-1", "alice moved to Berlin", 100);
            episode(AGENT, "s-alice-1", "alice likes tea", 200);
            episode(AGENT, "s-alice-2", "alice adopted a cat", 300);
            episode(AGENT, "s-bob-1", "bob likes coffee", 150);
        });
    }

    @Test
    void recentEpisodesAreScopedToOneIdentityAcrossSessionsAndOrderedByRecency() {
        List<RecentEpisode> alice = store.recentEpisodes("alice", AGENT, null, 10);

        assertEquals(3, alice.size(), "only Alice's episodes across her two sessions");
        assertEquals("alice adopted a cat", alice.get(0).content(), "most recent first (t=300)");
        assertEquals("alice likes tea", alice.get(1).content());
        assertEquals("alice moved to Berlin", alice.get(2).content());
        assertTrue(alice.stream().noneMatch(e -> e.content().contains("bob")),
                "another identity's episodes must never surface");
    }

    @Test
    void recentEpisodesHonorTheRowCap() {
        assertEquals(1, store.recentEpisodes("alice", AGENT, null, 1).size(), "limit caps the count");
    }

    @Test
    void recentEpisodesCanExcludeTheCurrentSession() {
        List<RecentEpisode> pastOnly = store.recentEpisodes("alice", AGENT, "s-alice-1", 10);

        assertEquals(1, pastOnly.size(), "excluding s-alice-1 leaves only the s-alice-2 episode");
        assertEquals("alice adopted a cat", pastOnly.get(0).content());
    }

    @Test
    void recentEpisodesForAnIdentityWithNoneIsEmpty() {
        assertTrue(store.recentEpisodes("carol", AGENT, null, 10).isEmpty());
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

    private static void episode(String agentId, String sessionId, String content, long createdAt) {
        EpisodicMemoryEntity episode = new EpisodicMemoryEntity();
        episode.agentId = agentId;
        episode.sessionId = sessionId;
        episode.eventType = "observation";
        episode.content = content;
        episode.createdAt = createdAt;
        episode.persist();
    }
}
