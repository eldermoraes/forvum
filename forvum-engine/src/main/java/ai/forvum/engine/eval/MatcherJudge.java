package ai.forvum.engine.eval;

/**
 * The deterministic, offline default judge (P3-10 #58): it scores a reply by the scenario's own
 * {@link MatchMode} ({@code contains}/{@code exact}/{@code regex}) with NO live model, so an eval suite
 * runs as a CI quality gate without inference (Risk #10 — the LLM judge is opt-in only). Pure and
 * stateless: judge-vs-human agreement is perfect by construction.
 */
public final class MatcherJudge implements EvalJudge {

    @Override
    public String label() {
        return "matcher";
    }

    @Override
    public Verdict judge(EvalScenario scenario, String reply) {
        String safeReply = reply == null ? "" : reply;
        boolean passed = scenario.match().matches(scenario.expect(), safeReply);
        String reason = passed
                ? "reply " + scenario.match().name().toLowerCase(java.util.Locale.ROOT)
                        + " '" + scenario.expect() + "'"
                : "expected " + scenario.match().name().toLowerCase(java.util.Locale.ROOT)
                        + " '" + scenario.expect() + "'";
        return new Verdict(passed, reason);
    }
}
