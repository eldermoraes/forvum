package ai.forvum.engine.eval;

import java.util.List;

/**
 * A parsed evaluation suite (P3-10 #58): the scenarios to run, the {@code agentId} to run them as, the
 * CAPR pass-rate {@code floor} the run must meet, and the optional {@code judge} ref selecting how each
 * reply is scored.
 *
 * <p><strong>CAPR gate.</strong> The run's CAPR is the fraction of passing scenarios; if it falls below
 * {@code floor} the run is a regression and {@code forvum eval} exits non-zero — a release/CI quality
 * gate on par with coverage (ULTRAPLAN §3.6 / §7.2, Risk #10).
 *
 * <p><strong>Judge (Risk #10).</strong> {@code judge} is {@code null} for the deterministic, offline
 * {@link MatcherJudge} (the default — no live model, runs in CI). An explicit {@code "llm:<provider>:
 * <model>"} ref opts into a pluggable LLM judge ({@link LlmJudge}); the LLM judge is NEVER forced into a
 * normal turn — it exists only inside the harness, opt-in, behind this field.
 *
 * @param name      the suite name (the {@code .json} file-name stem)
 * @param agentId   the agent the scenarios run as
 * @param floor     the minimum CAPR pass-rate, in {@code [0.0, 1.0]}, the run must reach
 * @param judge     the judge ref ({@code null} = the deterministic offline matcher)
 * @param scenarios the non-empty list of cases
 */
public record EvalSuite(String name, String agentId, double floor, String judge,
                        List<EvalScenario> scenarios) {

    public EvalSuite {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Eval suite name must be non-blank.");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalStateException("Eval suite '" + name + "' agent must be non-blank.");
        }
        if (Double.isNaN(floor) || floor < 0.0 || floor > 1.0) {
            throw new IllegalStateException(
                    "Eval suite '" + name + "' floor must be in [0.0, 1.0]; got " + floor + ".");
        }
        if (scenarios == null || scenarios.isEmpty()) {
            throw new IllegalStateException("Eval suite '" + name + "' must declare at least one scenario.");
        }
        scenarios = List.copyOf(scenarios);
    }
}
