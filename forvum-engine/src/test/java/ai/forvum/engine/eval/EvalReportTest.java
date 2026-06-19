package ai.forvum.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/** The aggregation + CAPR gate of {@link EvalReport} (P3-10 #58) — both gate directions. */
class EvalReportTest {

    private static ScenarioResult pass(String id) {
        return new ScenarioResult(id, true, "reply", "ok");
    }

    private static ScenarioResult fail(String id) {
        return new ScenarioResult(id, false, "reply", "no");
    }

    @Test
    void passRateIsPassingOverTotal() {
        EvalReport report = new EvalReport("s", "matcher", 0.5,
                List.of(pass("a"), pass("b"), fail("c"), fail("d")));
        assertEquals(2, report.passed());
        assertEquals(0.5, report.passRate(), 1e-9);
    }

    @Test
    void floorMetIsNotARegression() {
        // pass-rate 0.5 == floor 0.5 → meets the floor (not below), so no regression.
        EvalReport report = new EvalReport("s", "matcher", 0.5, List.of(pass("a"), fail("b")));
        assertFalse(report.regressed(), "pass-rate equal to the floor is not a regression");
    }

    @Test
    void belowFloorIsARegression() {
        // pass-rate 0.25 < floor 0.8 → regression (the gate fails).
        EvalReport report = new EvalReport("s", "matcher", 0.8,
                List.of(pass("a"), fail("b"), fail("c"), fail("d")));
        assertTrue(report.regressed(), "a pass-rate below the floor is a regression");
    }

    @Test
    void allPassMeetsAnyFloor() {
        EvalReport report = new EvalReport("s", "matcher", 1.0, List.of(pass("a"), pass("b")));
        assertEquals(1.0, report.passRate(), 1e-9);
        assertFalse(report.regressed());
    }

    @Test
    void rejectsEmptyResults() {
        assertThrows(IllegalStateException.class,
                () -> new EvalReport("s", "matcher", 0.5, List.of()));
    }

    @Test
    void rejectsBlankSuiteName() {
        assertThrows(IllegalStateException.class,
                () -> new EvalReport("  ", "matcher", 0.5, List.of(pass("a"))));
    }
}
