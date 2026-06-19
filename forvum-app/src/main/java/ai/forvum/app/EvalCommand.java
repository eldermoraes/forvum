package ai.forvum.app;

import ai.forvum.engine.eval.EvalReport;
import ai.forvum.engine.eval.EvalRunner;
import ai.forvum.engine.eval.ScenarioResult;

import jakarta.inject.Inject;

import picocli.CommandLine;

import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * {@code forvum eval <suite>} runs a CAPR-gated evaluation suite (P3-10 #58): it runs each scenario in
 * {@code eval/<suite>.json} as a turn, scores the replies (the deterministic offline matcher by default;
 * an opt-in LLM judge when the suite declares one), and compares the pass-rate to the suite's CAPR floor.
 *
 * <p><strong>The gate.</strong> A run whose pass-rate falls below the floor is a regression and exits
 * non-zero, so CI/release can gate on it like coverage (ULTRAPLAN §3.6, Risk #10). The per-scenario
 * summary + the verdict go to stdout; a failure to load/run the suite goes to stderr with exit 1.
 *
 * <p>Like {@code ask}/{@code replay}, {@code eval} is NOT a {@code CommandMode} one-shot — each scenario
 * is a real turn that needs Flyway/the DB, so it boots the full path.
 */
@CommandLine.Command(
        name = "eval",
        description = "Run a CAPR-gated evaluation suite and fail on a regression below its floor.")
public class EvalCommand implements Callable<Integer> {

    @Inject
    EvalRunner runner;

    @CommandLine.Parameters(
            arity = "1",
            paramLabel = "<suite>",
            description = "Eval suite name (reads eval/<suite>.json under $FORVUM_HOME).")
    String suite;

    @Override
    public Integer call() {
        EvalReport report;
        try {
            report = runner.run(suite);
        } catch (RuntimeException e) {
            System.err.println("eval failed: " + e.getMessage());
            return 1;
        }

        System.out.println("Forvum eval: suite '" + report.suiteName() + "' (judge: " + report.judge() + ")");
        for (ScenarioResult result : report.results()) {
            System.out.println("  [" + (result.passed() ? "PASS" : "FAIL") + "] "
                    + result.scenarioId() + " — " + result.detail());
        }
        System.out.printf(Locale.ROOT,
                "CAPR pass-rate: %.2f (%d/%d) vs floor %.2f%n",
                report.passRate(), report.passed(), report.results().size(), report.floor());

        if (report.regressed()) {
            System.out.println("REGRESSION: pass-rate below floor.");
            return 1;
        }
        System.out.println("OK: pass-rate meets the floor.");
        return 0;
    }
}
