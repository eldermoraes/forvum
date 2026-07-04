package ai.forvum.engine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.id.AgentId;
import ai.forvum.engine.persistence.SemanticMemoryEntity;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

/**
 * {@link MemoryWriter} end-to-end (#175): the off-turn Write phase extracts facts with the scripted model,
 * runs each through the pre-memory-write secret filter, embeds it, and upserts it — proven over real
 * SQLite. The async seam is pinned to a same-thread executor so the write is synchronous and deterministic.
 */
@QuarkusTest
@TestProfile(MemoryWriterTestProfile.class)
class MemoryWriterIT {

    private static final String AGENT = "writer-agent";

    @Inject
    MemoryWriter writer;

    @BeforeEach
    void setUp() {
        writer.executor = Runnable::run; // same-thread → synchronous, deterministic write
        writer.writeEnabled = true; // reset the kill-switch a prior test may have flipped
        QuarkusTransaction.requiringNew().run(() -> SemanticMemoryEntity.delete("agentId = ?1", AGENT));
    }

    @Test
    void writesAnExtractedFactEmbeddedSoALaterTurnCanRetrieveIt() {
        UUID turn = UUID.randomUUID();

        writer.writeFacts(new AgentId(AGENT), "u1", turn, "sess-1",
                "I just moved to Berlin", "Nice, Berlin is great!");

        List<SemanticMemoryEntity> rows = read("u1");
        assertEquals(1, rows.size());
        assertEquals("user.city", rows.get(0).key);
        assertEquals("Berlin", rows.get(0).value);
        assertNotNull(rows.get(0).embedding, "the fact is embedded on write (embed-on-write)");
        assertTrue(rows.get(0).embedding.length > 0);
        assertEquals("turn:" + turn, rows.get(0).source, "provenance records the source turn");
    }

    @Test
    void redactsASecretBeforePersistingItViaThePreMemoryWriteFilter() {
        writer.writeFacts(new AgentId(AGENT), "u1", UUID.randomUUID(), "sess-1",
                "secretcase please remember this", "ok");

        List<SemanticMemoryEntity> rows = read("u1");
        assertEquals(1, rows.size());
        assertFalse(rows.get(0).value.contains("sk-live-ABCDEFGHIJKLMNOP1234"),
                "the raw secret must be redacted before it is durably stored (pre-memory-write filter)");
        assertTrue(rows.get(0).value.contains("***"), "the secret span is masked");
    }

    @Test
    void writesNothingWhenTheModelExtractsNoFacts() {
        writer.writeFacts(new AgentId(AGENT), "u1", UUID.randomUUID(), "sess-1",
                "nothingcase just chatting", "cool");

        assertTrue(read("u1").isEmpty(), "an empty extraction persists no rows");
    }

    @Test
    void onTurnCompletedRespectsTheWriteEnabledKillSwitch() {
        writer.writeEnabled = false;

        writer.onTurnCompleted(new AgentId(AGENT), "u1", UUID.randomUUID(), "sess-1",
                "I just moved to Berlin", "ok");

        assertTrue(read("u1").isEmpty(), "with writing disabled, onTurnCompleted must be a no-op");
    }

    @Test
    void writesFromAThreadWithNoAmbientRequestContext() throws Exception {
        // The production async path runs on a virtual thread that does NOT inherit the turn's request
        // context; the extraction chat's provider_calls ledger write (a request-scoped EntityManager) and
        // the fact upsert would throw ContextNotActiveException unless the writer activates its own context.
        // The same-thread executor inherits the @QuarkusTest method's context, so it cannot catch this —
        // run on a fresh, context-less thread (as production does).
        Thread worker = new Thread(() -> writer.writeFacts(new AgentId(AGENT), "u1", UUID.randomUUID(),
                "sess-1", "I just moved to Berlin", "Nice!"));
        worker.start();
        worker.join();

        List<SemanticMemoryEntity> rows = read("u1");
        assertEquals(1, rows.size(),
                "a write on a context-less thread must still persist — the writer activates a request context");
        assertEquals("Berlin", rows.get(0).value);
    }

    private List<SemanticMemoryEntity> read(String identity) {
        return QuarkusTransaction.requiringNew()
                .call(() -> SemanticMemoryEntity.list("identityId = ?1 and agentId = ?2", identity, AGENT));
    }
}
