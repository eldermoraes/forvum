package ai.forvum.engine.eval;

/**
 * The outcome of one {@link EvalScenario} (P3-10 #58): whether the reply {@code passed} the judge, the
 * captured {@code reply} text, and a short human {@code detail} (the judge's reason, or the turn-failure
 * message). A failed turn (the agent emitted an {@code ErrorEvent}) is a non-passing result, not an
 * aborted run — every scenario contributes to the CAPR pass-rate.
 *
 * @param scenarioId the scenario id
 * @param passed     true when the judge accepted the reply
 * @param reply      the assistant reply (or the error message when the turn failed)
 * @param detail     a short reason for the verdict
 */
public record ScenarioResult(String scenarioId, boolean passed, String reply, String detail) {

    public ScenarioResult {
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new IllegalStateException("ScenarioResult scenarioId must be non-blank.");
        }
    }
}
