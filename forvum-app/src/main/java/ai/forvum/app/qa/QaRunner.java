package ai.forvum.app.qa;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.engine.eval.MatchMode;
import ai.forvum.sdk.ApprovalContext;
import ai.forvum.sdk.ChannelTurnDriver;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs QA scenarios through the real turn path and checks each reply against its expectation (P2-QA,
 * ULTRAPLAN §7.2 item 18). Each scenario drives one turn via the SDK {@link ChannelTurnDriver} (the engine's
 * {@code TurnService} — the same seam {@code AskCommand} and every channel use), so the suite exercises the
 * production graph/ledger/turn path, not a mock. CI needs NO live inference: the QA pack pins {@code main} to
 * the deterministic, network-free {@code echo} provider ({@link EchoModelProvider}), which ships in the
 * binary, so a scenario's {@code input} produces a predictable reply the expectation asserts.
 *
 * <p>Fails-by-default: an empty scenario list (a missing/empty pack) yields zero results, which the
 * {@link QaCommand} treats as a FAILED suite. A turn that errors (an {@code ErrorEvent}, or no terminal
 * event) is a failed scenario, never a skip.
 */
@ApplicationScoped
public class QaRunner {

    /**
     * The channel id presented to the turn for a QA-driven turn: the always-paired CLI device, so a seeded
     * {@code devices/} pairing config never refuses the suite (mirrors {@code AskCommand}).
     */
    private static final String QA_CHANNEL = ai.forvum.engine.pairing.DeviceRegistry.CLI;

    @Inject
    ChannelTurnDriver turns;

    /**
     * Run {@code scenarios}, optionally filtered to one {@code channel} (null/blank = all). Returns a result
     * per run scenario, in order. A scenario whose declared channel does not match {@code channel} is
     * excluded from the returned list (not run, not failed) — {@code QaCommand} handles "no matching
     * scenarios" as a suite failure.
     */
    public List<QaResult> run(List<QaScenario> scenarios, String channel) {
        List<QaResult> results = new ArrayList<>();
        for (QaScenario scenario : scenarios) {
            if (channel != null && !channel.isBlank() && !channel.equals(scenario.channel())) {
                continue;
            }
            results.add(runOne(scenario));
        }
        return results;
    }

    private QaResult runOne(QaScenario scenario) {
        String id = scenario.id() == null || scenario.id().isBlank() ? "<unnamed>" : scenario.id();
        if (scenario.prompt() == null) {
            return QaResult.fail(id, "", "scenario has no prompt");
        }
        if (scenario.expect() == null) {
            return QaResult.fail(id, "", "scenario has no expectation");
        }

        // A distinct session per scenario id, so suite scenarios never share a conversation window.
        ChannelMessage message = new ChannelMessage(
                QA_CHANNEL, "qa-" + id, scenario.prompt(), Instant.now());

        String[] reply = {null};
        String[] error = {null};
        // No human at the keyboard during a suite run, and no dashboard requester, so a confirm-required
        // tool must deny immediately rather than block forever (mirrors AskCommand).
        ScopedValue.where(ApprovalContext.NON_INTERACTIVE, Boolean.TRUE).run(() ->
                turns.dispatch(message, event -> capture(event, reply, error)));

        if (error[0] != null) {
            return QaResult.fail(id, "", "turn failed: " + error[0]);
        }
        if (reply[0] == null) {
            return QaResult.fail(id, "", "turn produced no reply");
        }
        try {
            // ONE shared matcher (docs/SCENARIO-FORMAT.md): the engine MatchMode the eval suite also uses.
            // A blank/absent match defaults to contains (the documented suite default); a bad token throws,
            // turning the scenario into a fail rather than a vacuous pass (fails-by-default).
            MatchMode match = scenario.match() == null || scenario.match().isBlank()
                    ? MatchMode.CONTAINS
                    : MatchMode.fromWire(scenario.match());
            return match.satisfiedBy(scenario.expect(), reply[0])
                    ? QaResult.pass(id, reply[0])
                    : QaResult.fail(id, reply[0],
                            "expected " + match.name().toLowerCase(java.util.Locale.ROOT)
                            + " '" + scenario.expect() + "'");
        } catch (RuntimeException e) {
            return QaResult.fail(id, reply[0], "bad expectation: " + e.getMessage());
        }
    }

    private static void capture(AgentEvent event, String[] reply, String[] error) {
        switch (event) {
            case Done done -> reply[0] = done.finalMessage();
            case ErrorEvent err -> error[0] = "[" + err.code() + "] " + err.message();
            default -> {
                // Intermediate events (TokenDelta, tool events) are not the verdict; Done carries the reply.
            }
        }
    }
}
