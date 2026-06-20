package ai.forvum.app.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.event.Done;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.core.event.TokenDelta;
import ai.forvum.sdk.ChannelTurnDriver;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Unit tests for {@link QaRunner}'s verdict logic, with a hand-stubbed {@link ChannelTurnDriver} (no CDI,
 * no live model). Covers the pass/mismatch/error/no-reply/malformed-scenario branches and channel filtering
 * — the green-for-wrong-reason guard ([M18]): the stub echoes the input so the expectation is asserted
 * against the actual reply, not a constant. The match semantics live in the shared engine {@code MatchMode}
 * (tested by {@code MatchModeTest}/{@code MatcherJudgeTest}); this asserts only the runner wiring onto it.
 */
class QaRunnerTest {

    /** A runner whose driver replies with {@code reply} as a Done (mimicking the echo provider's egress). */
    private static QaRunner runnerReplying(String reply) {
        QaRunner runner = new QaRunner();
        runner.turns = doneDriver(reply);
        return runner;
    }

    @Test
    void aMatchingScenarioPasses() {
        QaRunner runner = new QaRunner();
        runner.turns = echoDriver(); // reply = "echo: " + input, so the expectation hits the real reply
        QaResult r = only(runner, scenario("s1", "cli", "hello", "echo: hello", "exact"));
        assertTrue(r.passed(), () -> "detail: " + r.detail());
        assertEquals("echo: hello", r.actual());
    }

    @Test
    void aBlankMatchDefaultsToContains() {
        QaResult r = only(runnerReplying("echo: hello world"),
                scenario("s1", "cli", "hello", "hello world", null));
        assertTrue(r.passed(), () -> "a missing match must default to contains; detail: " + r.detail());
    }

    @Test
    void aMismatchFails() {
        QaResult r = only(runnerReplying("echo: hello"),
                scenario("s1", "cli", "hello", "WRONG", "exact"));
        assertFalse(r.passed());
        assertTrue(r.detail().contains("expected exact 'WRONG'"), () -> r.detail());
    }

    @Test
    void anErrorEventFailsTheScenario() {
        QaRunner runner = new QaRunner();
        runner.turns = errorDriver("boom");
        QaResult r = only(runner, scenario("s1", "cli", "hi", "x", "exact"));
        assertFalse(r.passed());
        assertTrue(r.detail().contains("turn failed"), () -> r.detail());
    }

    @Test
    void noTerminalEventFailsTheScenario() {
        QaRunner runner = new QaRunner();
        runner.turns = (message, sink) -> { /* emits nothing terminal */ };
        QaResult r = only(runner, scenario("s1", "cli", "hi", "x", "exact"));
        assertFalse(r.passed());
        assertTrue(r.detail().contains("no reply"), () -> r.detail());
    }

    @Test
    void aNullPromptScenarioFails() {
        QaResult r = only(runnerReplying("x"), scenario("s1", "cli", null, "x", "exact"));
        assertFalse(r.passed());
        assertTrue(r.detail().contains("no prompt"), () -> r.detail());
    }

    @Test
    void aNullExpectationScenarioFails() {
        QaResult r = only(runnerReplying("x"), scenario("s1", "cli", "hi", null, "exact"));
        assertFalse(r.passed());
        assertTrue(r.detail().contains("no expectation"), () -> r.detail());
    }

    @Test
    void aBadMatchTokenFailsRatherThanThrows() {
        QaResult r = only(runnerReplying("x"), scenario("s1", "cli", "hi", "x", "equals"));
        assertFalse(r.passed());
        assertTrue(r.detail().contains("bad expectation"), () -> r.detail());
    }

    @Test
    void channelFilterExcludesNonMatchingScenarios() {
        List<QaResult> results = runnerReplying("echo: hi").run(List.of(
                scenario("cli-one", "cli", "hi", "echo: hi", "exact"),
                scenario("tg-one", "telegram", "hi", "echo: hi", "exact")),
                "cli");
        assertEquals(1, results.size(), "only the cli scenario runs under the cli filter");
        assertEquals("cli-one", results.get(0).scenarioId());
    }

    @Test
    void aBlankIdScenarioIsLabelledUnnamed() {
        QaResult r = only(runnerReplying("echo: hi"),
                scenario("  ", "cli", "hi", "echo: hi", "exact"));
        assertEquals("<unnamed>", r.scenarioId());
    }

    private static QaResult only(QaRunner runner, QaScenario scenario) {
        List<QaResult> results = runner.run(List.of(scenario), null);
        assertEquals(1, results.size());
        return results.get(0);
    }

    private static QaScenario scenario(String id, String channel, String prompt, String expect, String match) {
        return new QaScenario(id, channel, prompt, expect, match);
    }

    private static ChannelTurnDriver doneDriver(String reply) {
        return (message, sink) -> {
            sink.accept(new TokenDelta(Instant.now(), reply, null));
            sink.accept(new Done(Instant.now(), UUID.randomUUID(), reply));
        };
    }

    private static ChannelTurnDriver echoDriver() {
        return (message, sink) -> {
            String reply = "echo: " + message.content();
            sink.accept(new Done(Instant.now(), UUID.randomUUID(), reply));
        };
    }

    private static ChannelTurnDriver errorDriver(String detail) {
        return (message, sink) -> sink.accept(
                ErrorEvent.from(Instant.now(), UUID.randomUUID(), "turn_failed", detail, null));
    }
}
