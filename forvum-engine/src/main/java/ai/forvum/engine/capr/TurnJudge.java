package ai.forvum.engine.capr;

import ai.forvum.core.ModelRef;
import ai.forvum.engine.eval.EvalJudge;
import ai.forvum.engine.eval.EvalScenario;
import ai.forvum.engine.eval.LlmJudge;
import ai.forvum.engine.eval.MatchMode;
import ai.forvum.engine.model.FailureClass;
import ai.forvum.engine.model.FailureClassifier;
import ai.forvum.engine.persistence.CaprRecorder;
import ai.forvum.engine.routing.LlmSelector;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The real per-turn judge (#195): replaces the always-passed {@code capr_events} verdict stub. When
 * enabled it scores each completed turn's reply against the turn's user request with the existing eval
 * {@link LlmJudge} (reused, not a bespoke endpoint) running on the small-and-fast proxy model via
 * {@link LlmSelector#resolve} — OFF the turn's critical path, on a virtual thread — and writes a genuine
 * pass/fail + normalized {@code [0,1]} score into {@code capr_events}, attributed to the model that
 * answered the turn (the last successful {@code provider_calls} row of the session). That verdict feeds
 * {@code ModelHealthReader} → {@code LlmSelector}, so CAPR-driven routing reflects answer quality, not
 * just call health.
 *
 * <p><b>Off by default.</b> {@code forvum.capr.judge.enabled=false}: the disabled path preserves the
 * prior behavior verbatim — a synchronous {@link CaprRecorder#recordPassed} neutral row — so nothing
 * regresses (no latency, no extra model call, no cold-start cost). When the judge model itself is
 * unavailable, the verdict degrades to the same neutral unattributed row with a
 * {@link FailureClass}-compatible token in the rationale, so a judge outage never punishes the
 * answering model's health.
 */
@ApplicationScoped
public class TurnJudge {

    private static final Logger LOG = Logger.getLogger(TurnJudge.class);

    @Inject
    LlmSelector llmSelector;

    @Inject
    CaprRecorder caprRecorder;

    @Inject
    FailureClassifier classifier;

    /** Master switch — OFF by default so a normal turn pays no judge call (see class javadoc). */
    @ConfigProperty(name = "forvum.capr.judge.enabled", defaultValue = "false")
    boolean enabled;

    /** The judge's proxy model ref; defaults to the section-1.4 small-and-fast model. */
    @ConfigProperty(name = "forvum.capr.judge.model", defaultValue = "ollama:qwen3:1.7b")
    String judgeModel;

    /**
     * The async seam — a virtual-thread-per-task executor in production, overridden with a same-thread
     * executor ({@code Runnable::run}) in tests so the verdict is deterministically observable (the
     * {@code MemoryWriter.executor} pattern, CLAUDE.md [P2-14]).
     */
    Executor executor = Executors.newVirtualThreadPerTaskExecutor();

    /** Test seam: a scripted judge replaces the {@link LlmJudge} so tests run no live model. */
    EvalJudge judgeOverride;

    /**
     * Record the CAPR verdict for a completed turn. Disabled (the default): the prior neutral
     * {@code recordPassed} row is written synchronously, unchanged. Enabled: returns immediately and the
     * judging + verdict write happen on a virtual thread, off the turn's critical path.
     */
    public void onTurnCompleted(String sessionId, String agentId, long turnId, String userText,
            String reply) {
        if (!enabled) {
            caprRecorder.recordPassed(sessionId, agentId, turnId);
            return;
        }
        executor.execute(() -> judgeTurn(sessionId, agentId, turnId, userText, reply));
    }

    /** The judge body — package-private so a test can drive it directly under a same-thread executor. */
    void judgeTurn(String sessionId, String agentId, long turnId, String userText, String reply) {
        // Activate a request context on this async VT: the judge chat's provider_calls ledger write and
        // the verdict write need one, and the turn thread's context does not cross to this VT.
        ManagedContext requestContext = Arc.container().requestContext();
        boolean activatedHere = !requestContext.isActive();
        if (activatedHere) {
            requestContext.activate();
        }
        try {
            // Resolve the answering model BEFORE the judge chat — the judge's own call appends to the
            // same provider_calls ledger and must not shadow the turn's model.
            String answeringModel = caprRecorder.lastAnsweringModel(sessionId, agentId);
            EvalJudge judge = judgeOverride != null ? judgeOverride : llmJudge(agentId, sessionId);
            // The turn's "expectation" is the user's request itself: the judge answers whether the reply
            // satisfies it (MatchMode is required by the scenario record but unused by the LLM judge).
            EvalScenario scenario = new EvalScenario("turn-" + turnId, userText, userText,
                    MatchMode.CONTAINS);
            EvalJudge.Verdict raw = judge.judge(scenario, reply);
            TurnVerdict verdict = new TurnVerdict(raw.passed(), raw.passed() ? 1.0 : 0.0, raw.reason());
            caprRecorder.recordVerdict(sessionId, agentId, turnId, answeringModel, judge.label(), verdict);
        } catch (RuntimeException e) {
            // A judge outage never punishes the answering model: degrade to the neutral unattributed row
            // with a FailureClass-compatible token, mirroring the disabled path's shape.
            String failureClass = classToken(classifier.classify(e));
            LOG.warnf("Turn judge unavailable for turn %d (%s): %s", turnId, failureClass, e.toString());
            try {
                caprRecorder.recordJudgeUnavailable(sessionId, agentId, turnId, failureClass,
                        e.toString());
            } catch (RuntimeException persistFailure) {
                LOG.errorf(persistFailure, "Failed to record the judge-unavailable verdict for turn %d.",
                        turnId);
            }
        } finally {
            if (activatedHere) {
                requestContext.terminate();
            }
        }
    }

    /** Build the reused eval {@link LlmJudge} over the proxy model, fallback-wrapped and ledgered. */
    private EvalJudge llmJudge(String agentId, String sessionId) {
        ModelRef ref = ModelRef.parse(judgeModel);
        return new LlmJudge("llm:" + ref, llmSelector.resolve(ref, agentId, sessionId));
    }

    /** The {@link FailureClass} rationale token: {@code retryable}/{@code non_retryable}/{@code unknown}. */
    static String classToken(FailureClass failureClass) {
        return switch (failureClass) {
            case FailureClass.Retryable r -> "retryable";
            case FailureClass.NonRetryable n -> "non_retryable";
            default -> "unknown";
        };
    }
}
