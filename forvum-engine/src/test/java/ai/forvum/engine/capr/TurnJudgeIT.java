package ai.forvum.engine.capr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.engine.eval.EvalJudge;
import ai.forvum.engine.eval.EvalScenario;
import ai.forvum.engine.persistence.CaprEventEntity;
import ai.forvum.engine.persistence.PersistenceTestHomeProfile;
import ai.forvum.engine.persistence.ProviderCallEntity;

import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * {@link TurnJudge} over real SQLite (#195): disabled (the default) it preserves the prior neutral
 * passed row verbatim; enabled it writes a REAL verdict that varies with the reply — pass/fail +
 * normalized score, attributed to the answering model from the {@code provider_calls} ledger — and a
 * judge outage degrades to the neutral unattributed row with a FailureClass-compatible rationale. The
 * async seam is pinned to a same-thread executor so the verdict is deterministically observable.
 */
@QuarkusTest
@TestProfile(PersistenceTestHomeProfile.class)
class TurnJudgeIT {

    private static final String AGENT = "judge-it-agent";
    private static final String SESSION = "judge-it-session";

    /** A deterministic scripted judge: PASS iff the reply contains "good" (no live model). */
    private static final EvalJudge SCRIPTED = new EvalJudge() {
        @Override
        public String label() {
            return "scripted";
        }

        @Override
        public Verdict judge(EvalScenario scenario, String reply) {
            boolean passed = reply != null && reply.contains("good");
            return new Verdict(passed, passed ? "scripted: PASS" : "scripted: FAIL");
        }
    };

    private static final EvalJudge THROWING = new EvalJudge() {
        @Override
        public String label() {
            return "throwing";
        }

        @Override
        public Verdict judge(EvalScenario scenario, String reply) {
            throw new IllegalStateException("judge model unreachable");
        }
    };

    @Inject
    TurnJudge injected;

    /** The contextual instance — field writes on the client proxy would not reach the bean. */
    private TurnJudge judge;

    @BeforeEach
    void setUp() {
        judge = ClientProxy.unwrap(injected);
        judge.executor = Runnable::run; // same-thread → synchronous, deterministic verdict
        QuarkusTransaction.requiringNew().run(() -> {
            CaprEventEntity.delete("agentId", AGENT);
            ProviderCallEntity.delete("agentId", AGENT);
        });
    }

    @AfterEach
    void tearDown() {
        judge.enabled = false; // restore the shipped default for other tests
        judge.judgeOverride = null;
    }

    @Test
    void disabledByDefaultWritesTheNeutralPassedRowSynchronously() {
        judge.onTurnCompleted(SESSION, AGENT, 11L, "hi", "hello");

        CaprEventEntity row = onlyRow();
        assertEquals(1, row.passed);
        assertEquals("none", row.judgeModel);
        assertNull(row.model, "a disabled-judge row is never attributed to a model");
        assertNull(row.score, "a disabled-judge row carries no score");
    }

    @Test
    void enabledWritesARealVerdictThatVariesAttributedToTheAnsweringModel() {
        seedSuccessfulCall("ollama", "answering-model");
        judge.enabled = true;
        judge.judgeOverride = SCRIPTED;

        judge.onTurnCompleted(SESSION, AGENT, 21L, "say something nice", "a good reply");
        judge.onTurnCompleted(SESSION, AGENT, 22L, "say something nice", "a terrible reply");

        List<CaprEventEntity> rows = rows();
        assertEquals(2, rows.size());
        CaprEventEntity pass = byTurn(rows, 21L);
        assertEquals(1, pass.passed);
        assertEquals(1.0, pass.score);
        assertEquals("scripted", pass.judgeModel);
        assertEquals("ollama:answering-model", pass.model,
                "the verdict is attributed to the last successful provider call of the session");
        CaprEventEntity fail = byTurn(rows, 22L);
        assertEquals(0, fail.passed, "the verdict varies with the reply — no constant-passed stub");
        assertEquals(0.0, fail.score);
        assertEquals("scripted: FAIL", fail.rationale);
    }

    @Test
    void aJudgeOutageDegradesToTheNeutralUnattributedRow() {
        seedSuccessfulCall("ollama", "answering-model");
        judge.enabled = true;
        judge.judgeOverride = THROWING;

        judge.onTurnCompleted(SESSION, AGENT, 31L, "hi", "hello");

        CaprEventEntity row = onlyRow();
        assertEquals(1, row.passed, "a judge outage never punishes the answering model");
        assertEquals("none", row.judgeModel);
        assertNull(row.model);
        assertNull(row.score);
        assertTrue(row.rationale.contains("judge unavailable (unknown)"),
                "the rationale carries a FailureClass-compatible token, got: " + row.rationale);
    }

    @Test
    void anEmptyLedgerLeavesTheVerdictUnattributed() {
        judge.enabled = true;
        judge.judgeOverride = SCRIPTED;

        judge.onTurnCompleted(SESSION, AGENT, 41L, "hi", "a good reply");

        CaprEventEntity row = onlyRow();
        assertEquals(1, row.passed);
        assertNull(row.model, "no successful provider call in the session → no model attribution");
    }

    private void seedSuccessfulCall(String provider, String model) {
        QuarkusTransaction.requiringNew().run(() -> {
            ProviderCallEntity p = new ProviderCallEntity();
            p.sessionId = SESSION;
            p.agentId = AGENT;
            p.provider = provider;
            p.model = model;
            p.tokensIn = 5;
            p.tokensOut = 5;
            p.latencyMs = 1;
            p.isFallback = 0;
            p.createdAt = System.currentTimeMillis();
            p.persist();
        });
    }

    private List<CaprEventEntity> rows() {
        return QuarkusTransaction.requiringNew()
                .call(() -> CaprEventEntity.list("agentId", AGENT));
    }

    private CaprEventEntity onlyRow() {
        List<CaprEventEntity> rows = rows();
        assertEquals(1, rows.size(), "exactly one capr_events row per completed turn");
        return rows.get(0);
    }

    private static CaprEventEntity byTurn(List<CaprEventEntity> rows, long turnId) {
        return rows.stream().filter(r -> r.turnId == turnId).findFirst().orElseThrow();
    }
}
