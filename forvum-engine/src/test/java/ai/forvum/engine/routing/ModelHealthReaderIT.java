package ai.forvum.engine.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ModelRef;
import ai.forvum.engine.persistence.PersistenceTestHomeProfile;
import ai.forvum.engine.persistence.ProviderCallEntity;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * The P3-4 #52 acceptance through the real SQLite ledger: seed {@code provider_calls} with a low-pass-rate
 * model and a healthy one for an agent, then assert (1) {@link ModelHealthReader} tallies attempts/failures
 * per model from the {@code error} column and (2) feeding that health to a real {@link CaprRouter}
 * DOWN-RANKS the sagging model in the resolution order. Surefire-run (headless library).
 */
@QuarkusTest
@TestProfile(PersistenceTestHomeProfile.class)
class ModelHealthReaderIT {

    @Inject
    ModelHealthReader reader;

    private static final String AGENT = "router-it-agent";
    private static final ModelRef HEALTHY = new ModelRef("ollama", "good-it");
    private static final ModelRef SAGGING = new ModelRef("ollama", "bad-it");

    /** Persist one provider_calls row; {@code error != null} marks a failed call (no @Transactional). */
    private void call(String agentId, ModelRef ref, String error) {
        ProviderCallEntity p = new ProviderCallEntity();
        p.sessionId = "router-it-session";
        p.agentId = agentId;
        p.provider = ref.provider();
        p.model = ref.model();
        p.tokensIn = error == null ? 5 : 0;
        p.tokensOut = error == null ? 5 : 0;
        p.latencyMs = 1;
        p.isFallback = 0;
        p.error = error;
        p.createdAt = System.currentTimeMillis();
        p.persist();
    }

    @Test
    @Transactional
    void saggingModelTallyDownRanksItBelowHealthy() {
        ProviderCallEntity.delete("agentId", AGENT);

        // HEALTHY: 8 calls, all succeed.
        for (int i = 0; i < 8; i++) {
            call(AGENT, HEALTHY, null);
        }
        // SAGGING: 8 calls, 7 fail.
        for (int i = 0; i < 7; i++) {
            call(AGENT, SAGGING, "java.lang.RuntimeException");
        }
        call(AGENT, SAGGING, null);

        Map<ModelRef, ModelHealth> health = reader.health(AGENT, List.of(SAGGING, HEALTHY));

        // (1) The per-model tally reflects the seeded error column.
        ModelHealth sag = health.get(SAGGING);
        ModelHealth ok = health.get(HEALTHY);
        assertEquals(8, sag.attempts());
        assertEquals(7, sag.failures());
        assertEquals(8, ok.attempts());
        assertEquals(0, ok.failures());

        // (2) A real router, fed that health, flips the declared (SAGGING-first) order.
        CaprRouter router = new CaprRouter(true, 0.7, 3);
        List<ModelRef> ordered = router.reorder(List.of(SAGGING, HEALTHY), health);
        assertEquals(List.of(HEALTHY, SAGGING), ordered,
                "the low-pass-rate model must be down-ranked below the healthy one");
    }

    @Test
    @Transactional
    void agentScopingIsolatesHealth() {
        ProviderCallEntity.delete("agentId", AGENT);
        ProviderCallEntity.delete("agentId", AGENT + "-other");

        // SAGGING fails 5/5 for AGENT but is perfect for a DIFFERENT agent.
        for (int i = 0; i < 5; i++) {
            call(AGENT, SAGGING, "boom");
            call(AGENT + "-other", SAGGING, null);
        }

        Map<ModelRef, ModelHealth> health = reader.health(AGENT, List.of(SAGGING));
        ModelHealth sag = health.get(SAGGING);
        assertEquals(5, sag.attempts());
        assertEquals(5, sag.failures(), "only THIS agent's failures count");
    }

    @Test
    @Transactional
    void noRecordedCallsAbsentFromHealthMap() {
        ProviderCallEntity.delete("agentId", AGENT);

        Map<ModelRef, ModelHealth> health = reader.health(AGENT, List.of(HEALTHY, SAGGING));
        assertTrue(health.isEmpty(), "a cold-start model has no health entry (router treats it neutral)");
        assertNull(health.get(HEALTHY));
        assertFalse(health.containsKey(SAGGING));
    }
}
