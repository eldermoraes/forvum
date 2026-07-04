package ai.forvum.engine.memoryquery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.engine.persistence.SemanticMemoryEntity;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * {@link SemanticMemoryStore#upsertFact} is the off-turn write path of #175: it persists a fact WITH its
 * embedding, upserts deterministically on {@code (identity, agent, key)}, and keeps identities isolated —
 * all with identity/agent passed explicitly (no ScopedValue), so the async memory writer can call it on a
 * plain virtual thread. Real SQLite via the seeded temp home.
 */
@QuarkusTest
@TestProfile(MemoryQueryTestHomeProfile.class)
class SemanticMemoryStoreUpsertIT {

    private static final String AGENT = "upsert-agent";

    @Inject
    SemanticMemoryStore store;

    @BeforeEach
    void clean() {
        QuarkusTransaction.requiringNew().run(() -> SemanticMemoryEntity.delete("agentId = ?1", AGENT));
    }

    @Test
    void upsertInsertsANewFactWithItsEmbedding() {
        byte[] embedding = {1, 2, 3, 4};
        store.upsertFact("u1", AGENT, "user.city", "Berlin", "turn-1", embedding);

        List<SemanticMemoryEntity> rows = read("u1");
        assertEquals(1, rows.size());
        assertEquals("Berlin", rows.get(0).value);
        assertEquals("turn-1", rows.get(0).source);
        assertArrayEquals(embedding, rows.get(0).embedding, "the embedding is persisted on write");
    }

    @Test
    void upsertUpdatesTheSameKeyRatherThanInsertingASecondRow() {
        store.upsertFact("u1", AGENT, "user.city", "Berlin", "turn-1", new byte[] {1});
        store.upsertFact("u1", AGENT, "user.city", "Munich", "turn-2", new byte[] {2});

        List<SemanticMemoryEntity> rows = read("u1");
        assertEquals(1, rows.size(), "the same (identity,agent,key) upserts, never a second row");
        assertEquals("Munich", rows.get(0).value, "the latest value wins");
        assertArrayEquals(new byte[] {2}, rows.get(0).embedding, "the embedding is refreshed on update");
    }

    @Test
    void upsertKeepsTwoIdentitiesWithTheSameKeyIsolated() {
        store.upsertFact("alice", AGENT, "name", "Alice", "t", new byte[] {1});
        store.upsertFact("bob", AGENT, "name", "Bob", "t", new byte[] {2});

        assertEquals("Alice", read("alice").get(0).value);
        assertEquals("Bob", read("bob").get(0).value);
    }

    @Test
    void deleteFactRemovesOnlyTheNamedKeyForThatIdentity() {
        store.upsertFact("u1", AGENT, "user.city", "Berlin", "t", null);
        store.upsertFact("u1", AGENT, "user.color", "blue", "t", null);
        store.upsertFact("u2", AGENT, "user.city", "Paris", "t", null);

        assertEquals(1, store.deleteFact("u1", AGENT, "user.city"), "exactly one row removed");

        assertEquals(1, read("u1").size(), "only user.color remains for u1");
        assertEquals("user.color", read("u1").get(0).key);
        assertEquals(1, read("u2").size(), "u2's same-key fact is never touched");
        assertEquals(0, store.deleteFact("u1", AGENT, "nonexistent"), "deleting a missing key removes nothing");
    }

    @Test
    void deleteAllFactsClearsOneIdentityOnly() {
        store.upsertFact("u1", AGENT, "a", "1", "t", null);
        store.upsertFact("u1", AGENT, "b", "2", "t", null);
        store.upsertFact("u2", AGENT, "a", "1", "t", null);

        assertEquals(2, store.deleteAllFacts("u1", AGENT));

        assertTrue(read("u1").isEmpty(), "all of u1's facts are gone");
        assertEquals(1, read("u2").size(), "another identity is untouched");
    }

    private List<SemanticMemoryEntity> read(String identity) {
        return QuarkusTransaction.requiringNew()
                .call(() -> SemanticMemoryEntity.list("identityId = ?1 and agentId = ?2", identity, AGENT));
    }
}
