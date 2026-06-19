package ai.forvum.engine.eval;

import java.util.List;

/**
 * The aggregated result of running an {@link EvalSuite} (P3-10 #58): the per-scenario {@link #results}
 * and the {@link #floor} they were gated against. The CAPR {@link #passRate()} is the fraction of
 * passing scenarios; {@link #regressed()} is true when it falls below the floor — the signal
 * {@code forvum eval} turns into a non-zero exit so CI/release can gate (a quality gate on par with
 * coverage; ULTRAPLAN §3.6, Risk #10).
 *
 * @param suiteName the suite that was run
 * @param judge     the judge that scored it ({@code matcher} for the offline default, or the LLM ref)
 * @param floor     the CAPR floor the run was required to meet, in {@code [0.0, 1.0]}
 * @param results   the per-scenario outcomes (non-empty — a suite has at least one scenario)
 */
public record EvalReport(String suiteName, String judge, double floor, List<ScenarioResult> results) {

    public EvalReport {
        if (suiteName == null || suiteName.isBlank()) {
            throw new IllegalStateException("EvalReport suiteName must be non-blank.");
        }
        if (results == null || results.isEmpty()) {
            throw new IllegalStateException("EvalReport must carry at least one scenario result.");
        }
        results = List.copyOf(results);
    }

    /** How many scenarios passed the judge. */
    public long passed() {
        return results.stream().filter(ScenarioResult::passed).count();
    }

    /** The CAPR pass-rate: passing scenarios over total, in {@code [0.0, 1.0]}. */
    public double passRate() {
        return (double) passed() / results.size();
    }

    /** True when the pass-rate is below the floor — the regression that fails the gate. */
    public boolean regressed() {
        return passRate() < floor;
    }
}
