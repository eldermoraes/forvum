package ai.forvum.engine.eval;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.ModelRef;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.engine.config.ConfigLoader;
import ai.forvum.engine.config.ForvumHome;
import ai.forvum.engine.routing.LlmSelector;
import ai.forvum.sdk.ApprovalContext;
import ai.forvum.sdk.ChannelTurnDriver;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a CAPR-gated evaluation suite (P3-10 #58): loads {@code eval/<name>.json}, runs each scenario as a
 * real turn through the SDK {@link ChannelTurnDriver} (the same {@code TurnService} path {@code forvum
 * ask} uses — it binds the agent context, resolves identity, activates the request context, and ledgers
 * the turn), scores each reply with a pluggable {@link EvalJudge}, and aggregates a pass-rate compared
 * to the suite's CAPR floor into an {@link EvalReport}.
 *
 * <p><strong>CAPR gate.</strong> {@code forvum eval} fails (exits non-zero) when {@link EvalReport#regressed()}
 * is true — a release/CI quality gate (ULTRAPLAN §3.6 / §7.2, Risk #10). The harness computes the pass-rate
 * IN MEMORY and does not persist eval rows (no migration); each scenario's turn still writes its own
 * {@code messages}/{@code provider_calls}/{@code capr_events} the normal way.
 *
 * <p><strong>Judge (Risk #10).</strong> The default judge is the deterministic, offline {@link MatcherJudge}
 * (exact/contains/regex), so the suite runs as a CI gate with NO live model. A suite's explicit
 * {@code "judge": "llm:<provider>:<model>"} opts into the pluggable {@link LlmJudge} (a cheap local model);
 * the LLM judge is NEVER forced into a normal turn — it lives only here.
 *
 * <p>Each scenario runs in its own session ({@code eval:<suite>:<scenario>}), so replies never carry a
 * prior scenario's conversation. The eval turns bind {@link ApprovalContext#NON_INTERACTIVE} (like
 * {@code forvum ask}) — an eval run is unattended, so a confirm-required tool is declined, not parked.
 */
@ApplicationScoped
public class EvalRunner {

    /** The {@code judge} ref prefix selecting the pluggable LLM judge (Risk #10): {@code llm:provider:model}. */
    private static final String LLM_JUDGE_PREFIX = "llm:";

    @Inject
    ForvumHome home;

    @Inject
    ConfigLoader loader;

    @Inject
    ChannelTurnDriver turns;

    @Inject
    LlmSelector llmSelector;

    private final EvalSuiteReader reader = new EvalSuiteReader();

    /**
     * Load and run the suite named {@code suiteName} (reading {@code eval/<suiteName>.json}), returning its
     * aggregated report.
     *
     * @throws IllegalStateException if the suite file is absent or fails to parse (the {@code eval/<name>.json}
     *         hint rides the message so the operator can fix it)
     */
    public EvalReport run(String suiteName) {
        EvalSuite suite = load(suiteName);
        EvalJudge judge = judgeFor(suite);
        List<ScenarioResult> results = new ArrayList<>();
        for (EvalScenario scenario : suite.scenarios()) {
            results.add(score(suite, scenario, judge));
        }
        return new EvalReport(suite.name(), judge.label(), suite.floor(), results);
    }

    private EvalSuite load(String suiteName) {
        JsonNode spec = loader.readJson(home.eval().resolve(suiteName + ".json"))
                .orElseThrow(() -> new IllegalStateException(
                        "Eval suite '" + suiteName + "' not found. Create eval/" + suiteName + ".json under "
                      + home.eval() + "."));
        return reader.parse(suiteName, spec);
    }

    /** Resolve the suite's judge: the offline matcher by default, or a cheap LLM judge when opted in. */
    private EvalJudge judgeFor(EvalSuite suite) {
        String judge = suite.judge();
        if (judge == null) {
            return new MatcherJudge();
        }
        if (judge.startsWith(LLM_JUDGE_PREFIX)) {
            ModelRef ref = ModelRef.parse(judge.substring(LLM_JUDGE_PREFIX.length()));
            // Resolve through the routing layer so the judge call is fallback-wrapped + ledgered like any
            // turn; the session id keys the ledger to this eval suite.
            return new LlmJudge(judge, llmSelector.resolve(ref, suite.agentId(), "eval:" + suite.name()));
        }
        throw new IllegalStateException(
                "Eval suite '" + suite.name() + "' judge '" + judge + "' is not recognized "
              + "(use the offline default by omitting 'judge', or 'llm:<provider>:<model>').");
    }

    /** Run one scenario as a turn and score the reply; a failed turn is a non-passing result, not an abort. */
    private ScenarioResult score(EvalSuite suite, EvalScenario scenario, EvalJudge judge) {
        TurnCapture capture = runTurn(suite, scenario);
        if (!capture.ok()) {
            return new ScenarioResult(scenario.id(), false, capture.text(), "turn failed: " + capture.text());
        }
        EvalJudge.Verdict verdict = judge.judge(scenario, capture.text());
        return new ScenarioResult(scenario.id(), verdict.passed(), capture.text(), verdict.reason());
    }

    /** Drive one scenario through the turn driver, capturing the final reply (or the error message). */
    private TurnCapture runTurn(EvalSuite suite, EvalScenario scenario) {
        String channelId = "eval:" + suite.name() + ":" + scenario.id();
        ChannelMessage message = new ChannelMessage(channelId, "eval", scenario.prompt(), Instant.now());
        TurnCapture capture = new TurnCapture();
        ScopedValue.where(ApprovalContext.NON_INTERACTIVE, Boolean.TRUE).run(() ->
                turns.dispatch(message, event -> accept(capture, event)));
        return capture;
    }

    private static void accept(TurnCapture capture, AgentEvent event) {
        switch (event) {
            case Done done -> capture.succeed(done.finalMessage());
            case ErrorEvent error -> capture.fail(error.code() + ": " + error.message());
            default -> {
                // TokenDelta and tool events are intermediate; the final reply arrives on Done.
            }
        }
    }

    /** Mutable per-scenario turn capture: the terminal Done/ErrorEvent wins (last terminal event). */
    private static final class TurnCapture {
        private boolean ok;
        private String text = "";

        void succeed(String reply) {
            this.ok = true;
            this.text = reply == null ? "" : reply;
        }

        void fail(String reason) {
            this.ok = false;
            this.text = reason == null ? "" : reason;
        }

        boolean ok() {
            return ok;
        }

        String text() {
            return text;
        }
    }
}
