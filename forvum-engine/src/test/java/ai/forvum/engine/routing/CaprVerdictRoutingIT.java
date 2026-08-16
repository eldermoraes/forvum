package ai.forvum.engine.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ModelRef;
import ai.forvum.engine.persistence.CaprEventEntity;
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
 * The #195 acceptance through the real SQLite ledger: a model whose provider CALLS all succeed but whose
 * judged {@code capr_events} verdicts FAIL is demoted by CAPR routing — {@link ModelHealthReader} tallies
 * the real per-turn verdicts (the {@code model}-attributed rows written by {@code TurnJudge}) into
 * {@link ModelHealth}, and a real {@link CaprRouter} sinks it below a sibling with passing verdicts.
 * Placeholder rows (judge disabled — {@code model IS NULL}) and archived rows are never tallied.
 */
@QuarkusTest
@TestProfile(PersistenceTestHomeProfile.class)
class CaprVerdictRoutingIT {

    private static final String AGENT = "verdict-routing-agent";
    private static final ModelRef FAILING = new ModelRef("ollama", "verdict-failing");
    private static final ModelRef PASSING = new ModelRef("ollama", "verdict-passing");

    @Inject
    ModelHealthReader reader;

    @Test
    @Transactional
    void failingJudgeVerdictsDemoteAModelWhoseCallsAllSucceed() {
        CaprEventEntity.delete("agentId", AGENT);
        ProviderCallEntity.delete("agentId", AGENT);
        // Both models' provider CALLS all succeed — call health alone cannot tell them apart.
        for (int i = 0; i < 4; i++) {
            successfulCall(FAILING);
            successfulCall(PASSING);
        }
        // But the judge failed FAILING's replies and passed PASSING's.
        for (long turn = 1; turn <= 4; turn++) {
            verdict(FAILING, turn, false, false);
            verdict(PASSING, 100 + turn, true, false);
        }
        // Noise that must NOT tally: an archived failing verdict and an unattributed placeholder row.
        verdict(PASSING, 200L, false, true);
        placeholderRow(300L);

        Map<ModelRef, ModelHealth> health = reader.health(AGENT, List.of(FAILING, PASSING));

        ModelHealth failing = health.get(FAILING);
        assertEquals(8, failing.attempts(), "4 successful calls + 4 judged verdicts");
        assertEquals(4, failing.failures(), "every judged verdict failed");
        ModelHealth passing = health.get(PASSING);
        assertEquals(8, passing.attempts());
        assertEquals(0, passing.failures(), "archived and placeholder rows never tally");

        // Declared FAILING-first; the real verdicts must demote it below PASSING.
        CaprRouter router = new CaprRouter(true, 0.7, 3);
        List<ModelRef> ordered = router.reorder(List.of(FAILING, PASSING), health);
        assertEquals(List.of(PASSING, FAILING), ordered,
                "routing must reflect answer quality, not just call health");
        assertTrue(failing.passRate() < passing.passRate());
    }

    private void successfulCall(ModelRef ref) {
        ProviderCallEntity p = new ProviderCallEntity();
        p.sessionId = "verdict-routing-session";
        p.agentId = AGENT;
        p.provider = ref.provider();
        p.model = ref.model();
        p.tokensIn = 5;
        p.tokensOut = 5;
        p.latencyMs = 1;
        p.isFallback = 0;
        p.createdAt = System.currentTimeMillis();
        p.persist();
    }

    private void verdict(ModelRef ref, long turnId, boolean passed, boolean archived) {
        CaprEventEntity e = new CaprEventEntity();
        e.sessionId = "verdict-routing-session";
        e.agentId = AGENT;
        e.turnId = turnId;
        e.passed = passed ? 1 : 0;
        e.score = passed ? 1.0 : 0.0;
        e.model = ref.toString();
        e.judgeModel = "llm:ollama:judge";
        e.rationale = passed ? "judge: PASS" : "judge: FAIL";
        e.archived = archived;
        e.createdAt = System.currentTimeMillis();
        e.persist();
    }

    /** The disabled-judge neutral row: no model attribution, so it must never tally into health. */
    private void placeholderRow(long turnId) {
        CaprEventEntity e = new CaprEventEntity();
        e.sessionId = "verdict-routing-session";
        e.agentId = AGENT;
        e.turnId = turnId;
        e.passed = 1;
        e.judgeModel = "none";
        e.rationale = "judge mode disabled";
        e.createdAt = System.currentTimeMillis();
        e.persist();
    }
}
