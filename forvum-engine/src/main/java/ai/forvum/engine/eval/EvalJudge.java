package ai.forvum.engine.eval;

/**
 * Scores one scenario's reply (P3-10 #58). A judge is pluggable so the harness can run fully offline (the
 * deterministic {@link MatcherJudge}, the CI default — exact/contains/regex, NO live model) or, opt-in,
 * against a cheap local model ({@link LlmJudge}).
 *
 * <p><strong>Risk #10.</strong> An LLM judge is OFF by default and lives only inside the eval harness —
 * it is never wired into a normal turn. Judge-vs-human agreement should be measured and the judge
 * replaced if it falls below 0.7 (ULTRAPLAN §3.6 Risk #10); the deterministic matcher has perfect
 * agreement by construction, which is why it is the default for the CI gate.
 */
public interface EvalJudge {

    /** A short label identifying the judge in the report (e.g. {@code matcher} or {@code llm:ollama:...}). */
    String label();

    /** Score {@code reply} against {@code scenario}; the reason is for the per-scenario report line. */
    Verdict judge(EvalScenario scenario, String reply);

    /** A pass/fail decision with a short human reason. */
    record Verdict(boolean passed, String reason) {

        public Verdict {
            if (reason == null || reason.isBlank()) {
                throw new IllegalStateException("Verdict reason must be non-blank.");
            }
        }
    }
}
