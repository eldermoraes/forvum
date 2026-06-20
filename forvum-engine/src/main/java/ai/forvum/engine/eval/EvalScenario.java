package ai.forvum.engine.eval;

/**
 * One eval case (P3-10 #58): a {@code prompt} sent as a turn to the suite's agent, and the
 * {@code expect}ed property of the reply, scored under {@code match}.
 *
 * <p>The same scenario shape is the SHARED format proposed for the {@code forvum qa} suite (#43) — see
 * {@link EvalSuiteReader} for the on-disk JSON. A scenario is a plain Layer-2 record, hand-parsed from a
 * {@code JsonNode} (no Jackson reflective databind), so it carries no {@code @RegisterForReflection}.
 *
 * @param id     the case id (for the per-scenario session and the report line)
 * @param prompt the turn's user-message content
 * @param expect the expected reply property (a substring, exact string, or regex per {@code match})
 * @param match  how {@code expect} is compared to the reply
 */
public record EvalScenario(String id, String prompt, String expect, MatchMode match) {

    public EvalScenario {
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("Eval scenario id must be non-blank.");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("Eval scenario '" + id + "' prompt must be non-blank.");
        }
        if (expect == null || expect.isBlank()) {
            throw new IllegalStateException("Eval scenario '" + id + "' expect must be non-blank.");
        }
        if (match == null) {
            throw new IllegalStateException("Eval scenario '" + id + "' match must be non-null.");
        }
    }
}
