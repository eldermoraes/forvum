package ai.forvum.app;

import ai.forvum.app.qa.QaResult;
import ai.forvum.app.qa.QaRunner;
import ai.forvum.app.qa.QaScenario;
import ai.forvum.app.qa.QaScenarioLoader;

import jakarta.inject.Inject;

import picocli.CommandLine;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code forvum qa suite} / {@code forvum qa <channel>} (P2-QA, ULTRAPLAN §7.2 item 18): run the packaged QA
 * scenario pack through the real turn path and FAIL BY DEFAULT on any missing or failed scenario. {@code qa
 * suite} runs every scenario; {@code qa <channel>} runs only those whose declared channel matches. With
 * {@code --pack <file>} it runs an operator-supplied pack instead of the bundled one.
 *
 * <p>The suite needs NO live inference: the QA home seeds {@code main} pinned to the deterministic, bundled
 * {@code echo} provider, so every scenario is reproducible on the JVM jar and the native binary (the
 * {@code [NATIVE]} acceptance). The turn runs via the SDK {@code ChannelTurnDriver} ({@link QaRunner}), the
 * same seam {@code ask} uses, so it exercises production graph/ledger code.
 *
 * <p>Exit codes — fails-by-default: 0 only when at least one scenario ran AND every result passed; 1 when
 * any scenario failed, when no scenario matched (an empty/absent pack, or a channel with no scenarios), or
 * when the pack could not be loaded. A green exit is the gate: a script or the CI {@code qa.yml} step gates
 * on it. Logs go to stderr (the shipped binary's {@code %prod} config), the summary to stdout — so a CI step
 * reads the verdict cleanly.
 */
@CommandLine.Command(
        name = "qa",
        description = "Run the packaged QA scenario suite and fail by default on any missing/failed scenario.")
public class QaCommand implements Callable<Integer> {

    /** Positional target: {@code suite} (run all) or a channel id (run that channel's scenarios). */
    private static final String SUITE = "suite";

    @Inject
    QaScenarioLoader loader;

    @Inject
    QaRunner runner;

    @CommandLine.Parameters(
            arity = "1",
            paramLabel = "<target>",
            description = "'suite' to run every scenario, or a channel id (e.g. cli) to run that channel's.")
    String target;

    @CommandLine.Option(
            names = "--pack",
            paramLabel = "<file>",
            description = "Run scenarios from this pack file instead of the bundled qa/scenarios.json.")
    Path pack;

    @Override
    public Integer call() {
        return run(System.out, System.err);
    }

    /**
     * Run the suite, writing the summary to {@code out} and diagnostics/errors to {@code err}. Package-private
     * with explicit streams so the command flow is unit-testable without capturing the static System streams.
     */
    int run(PrintStream out, PrintStream err) {
        List<QaScenario> scenarios;
        try {
            scenarios = pack == null ? loader.loadPackaged() : loader.loadFrom(pack);
        } catch (RuntimeException e) {
            err.println("qa: could not load the scenario pack: " + e.getMessage());
            return 1;
        }

        String channelFilter = SUITE.equals(target) ? null : target;
        List<QaResult> results = runner.run(scenarios, channelFilter);

        if (results.isEmpty()) {
            // Fails-by-default: a missing/empty pack, or a channel target with no scenarios, is a FAILURE.
            err.println("qa: no scenarios ran for target '" + target + "'"
                    + (channelFilter == null
                            ? " (the pack is empty or absent)"
                            : " (no scenario declares channel '" + channelFilter + "')")
                    + " — failing by default.");
            return 1;
        }

        long passed = results.stream().filter(QaResult::passed).count();
        for (QaResult result : results) {
            if (result.passed()) {
                out.println("PASS " + result.scenarioId());
            } else {
                out.println("FAIL " + result.scenarioId() + " — " + result.detail());
                err.println("qa: scenario '" + result.scenarioId() + "' failed: " + result.detail()
                        + " | actual: " + result.actual());
            }
        }
        out.println("QA suite: " + passed + "/" + results.size() + " passed.");
        return passed == results.size() ? 0 : 1;
    }
}
