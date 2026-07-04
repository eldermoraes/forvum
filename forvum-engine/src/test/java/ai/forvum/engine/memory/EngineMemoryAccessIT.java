package ai.forvum.engine.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.MemoryHit;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.context.CurrentIdentity;
import ai.forvum.engine.persistence.SemanticMemoryEntity;
import ai.forvum.sdk.MemoryAccess;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

/**
 * {@link EngineMemoryAccess} — the #193 engine seam behind the model-callable {@code memory.save} /
 * {@code memory.recall} tool. Proves the save→recall round-trip inside an identity scope, that recall never
 * crosses a tenant boundary, and that every save is routed through the DR-6a pre-memory-write filter (a
 * secret is redacted before storage). Runs over real SQLite with the deterministic {@code fake-embed}
 * model, so an exact-text recall scores 1.0 (the {@link LocalMemoryProviderIT} setup).
 */
@QuarkusTest
@TestProfile(LocalMemoryTestProfile.class)
class EngineMemoryAccessIT {

    private static final AgentId AGENT = new AgentId("memtool-agent");

    @Inject
    MemoryAccess memory;

    @BeforeEach
    void clean() {
        QuarkusTransaction.requiringNew().run(() -> SemanticMemoryEntity.delete("agentId = ?1", AGENT.value()));
    }

    @Test
    void saveThenRecallRoundTripsWithinTheIdentityScope() {
        boolean stored = as("u1", () -> memory.save("fav-color", "blue"));
        assertTrue(stored, "a plain value is stored (not blocked by the pre-memory-write filter)");

        List<MemoryHit> hits = as("u1", () -> memory.recall("blue"));
        assertTrue(hits.stream().anyMatch(h -> "blue".equals(h.content())),
                "a fact saved via memory.save must be retrievable via memory.recall in the same scope");
    }

    @Test
    void recallNeverCrossesTheIdentityBoundary() {
        as("u1", () -> memory.save("fav-color", "blue"));

        List<MemoryHit> otherUser = as("u2", () -> memory.recall("blue"));
        assertTrue(otherUser.stream().noneMatch(h -> "blue".equals(h.content())),
                "u2 must never recall u1's saved fact — memory is identity-scoped");
    }

    @Test
    void saveRoutesTheValueThroughThePreMemoryWriteSecretFilter() {
        String secret = "the token is sk-abcdefghijklmnopqrstuvwx";
        boolean stored = as("u1", () -> memory.save("api-key", secret));
        assertTrue(stored, "the value is stored — the secret is redacted, not blocked");

        List<MemoryHit> hits = as("u1", () -> memory.recall(secret));
        assertFalse(hits.stream().anyMatch(h -> h.content().contains("sk-abcdefghijklmnopqrstuvwx")),
                "the raw secret must be redacted by the DR-6a pre-memory-write filter before storage");
    }

    @Test
    void recallOfABlankQueryDegradesToEmptyInsteadOfThrowing() {
        List<MemoryHit> hits = as("u1", () -> memory.recall("   "));
        assertTrue(hits.isEmpty(), "a blank recall query returns no hits, not an IllegalStateException");
    }

    private <T> T as(String identity, Supplier<T> body) {
        return ScopedValue.where(CurrentIdentity.CURRENT_IDENTITY_ID, identity)
                .where(CurrentAgent.CURRENT_AGENT, AGENT)
                .call(body::get);
    }
}
