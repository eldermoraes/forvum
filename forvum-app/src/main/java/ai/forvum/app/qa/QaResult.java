package ai.forvum.app.qa;

/**
 * The outcome of running one {@link QaScenario}: which scenario, whether it passed, the actual reply, and a
 * human-readable detail (the mismatch or the error). Printed by {@link QaCommand} as the per-scenario line.
 *
 * <p>A scenario fails (passed = false) when: the turn errored (no reply / an {@code ErrorEvent}), the reply
 * did not satisfy the expectation, or the scenario/expectation was malformed. Never a vacuous pass.
 */
public record QaResult(String scenarioId, boolean passed, String actual, String detail) {

    static QaResult pass(String id, String actual) {
        return new QaResult(id, true, actual, "ok");
    }

    static QaResult fail(String id, String actual, String detail) {
        return new QaResult(id, false, actual, detail);
    }
}
