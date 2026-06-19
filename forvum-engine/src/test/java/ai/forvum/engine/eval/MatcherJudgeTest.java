package ai.forvum.engine.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The deterministic offline {@link MatcherJudge} (P3-10 #58). */
class MatcherJudgeTest {

    private final MatcherJudge judge = new MatcherJudge();

    @Test
    void labelIsMatcher() {
        org.junit.jupiter.api.Assertions.assertEquals("matcher", judge.label());
    }

    @Test
    void passesWhenTheReplyMatches() {
        EvalScenario scenario = new EvalScenario("s1", "say pong", "pong", MatchMode.CONTAINS);
        EvalJudge.Verdict verdict = judge.judge(scenario, "PONG from the agent");
        assertTrue(verdict.passed());
        assertNotNull(verdict.reason());
    }

    @Test
    void failsWhenTheReplyDoesNotMatch() {
        EvalScenario scenario = new EvalScenario("s1", "say pong", "pong", MatchMode.EXACT);
        EvalJudge.Verdict verdict = judge.judge(scenario, "pong!");
        assertFalse(verdict.passed());
        assertTrue(verdict.reason().contains("pong"));
    }

    @Test
    void treatsANullReplyAsAFailureNotACrash() {
        EvalScenario scenario = new EvalScenario("s1", "say pong", "pong", MatchMode.CONTAINS);
        EvalJudge.Verdict verdict = judge.judge(scenario, null);
        assertFalse(verdict.passed());
    }
}
