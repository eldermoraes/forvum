package ai.forvum.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

/** Canonical-constructor validation of the eval value records (P3-10 #58). */
class EvalRecordsTest {

    @ParameterizedTest
    @ValueSource(strings = {"id", "prompt", "expect"})
    void scenarioRejectsEachBlankField(String blankField) {
        String id = blankField.equals("id") ? " " : "s";
        String prompt = blankField.equals("prompt") ? " " : "p";
        String expect = blankField.equals("expect") ? " " : "x";
        assertThrows(IllegalStateException.class,
                () -> new EvalScenario(id, prompt, expect, MatchMode.CONTAINS));
    }

    @Test
    void scenarioRejectsNullMatch() {
        assertThrows(IllegalStateException.class, () -> new EvalScenario("s", "p", "x", null));
    }

    @Test
    void suiteCopiesItsScenarioList() {
        List<EvalScenario> scenarios =
                new java.util.ArrayList<>(List.of(new EvalScenario("a", "p", "x", MatchMode.CONTAINS)));
        EvalSuite suite = new EvalSuite("s", "main", 0.5, null, scenarios);
        scenarios.clear();
        assertEquals(1, suite.scenarios().size(), "the suite must hold an immutable copy");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, Double.NaN})
    void suiteRejectsAnOutOfRangeFloor(double floor) {
        assertThrows(IllegalStateException.class, () ->
                new EvalSuite("s", "main", floor, null,
                        List.of(new EvalScenario("a", "p", "x", MatchMode.CONTAINS))));
    }

    @Test
    void suiteRejectsEmptyScenarios() {
        assertThrows(IllegalStateException.class, () -> new EvalSuite("s", "main", 0.5, null, List.of()));
    }

    @Test
    void verdictRejectsBlankReason() {
        assertThrows(IllegalStateException.class, () -> new EvalJudge.Verdict(true, " "));
    }

    @Test
    void scenarioResultRejectsBlankId() {
        assertThrows(IllegalStateException.class, () -> new ScenarioResult(" ", true, "r", "d"));
    }

    @Test
    void floorBoundsAreInclusive() {
        EvalSuite zero = new EvalSuite("s", "main", 0.0, null,
                List.of(new EvalScenario("a", "p", "x", MatchMode.CONTAINS)));
        EvalSuite one = new EvalSuite("s", "main", 1.0, null,
                List.of(new EvalScenario("a", "p", "x", MatchMode.CONTAINS)));
        assertTrue(zero.floor() == 0.0 && one.floor() == 1.0);
    }
}
