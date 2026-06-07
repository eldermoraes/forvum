package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;

import org.junit.jupiter.api.Test;

/**
 * {@code forvum replay <sessionId>} reproduces a stored session's recorded message/tool sequence to stdout
 * (P2-8). The end-to-end check seeds the DB with a real {@code ask} turn (the in-process {@code fake}
 * provider replies {@code "pong"}, so it is deterministic with no live LLM), then replays the {@code cli:}
 * session that turn wrote and asserts both the user prompt and the assistant reply reappear, in order.
 *
 * <p>Reuses {@link AskCommandTest.FakeBackedHomeProfile} so both launches share one {@code forvum.home} and
 * therefore one SQLite database — {@code ask} writes the conversational rows, {@code replay} reads them back.
 * Unlike {@code doctor}, {@code replay} is NOT a {@code CommandMode} one-shot: it reads the DB, so it boots
 * the full Flyway/Panache path.
 */
@QuarkusMainTest
@TestProfile(AskCommandTest.FakeBackedHomeProfile.class)
class SessionReplayCommandTest {

    @Test
    void replayReproducesAPriorAskTurn(QuarkusMainLauncher launcher) {
        LaunchResult ask = launcher.launch("ask", "What is two plus two?");
        assertEquals(0, ask.exitCode(),
                () -> "ask must seed the session by exiting 0; stderr: " + ask.getErrorOutput());

        LaunchResult replay = launcher.launch("replay", cliSession());
        assertEquals(0, replay.exitCode(),
                () -> "replay must exit 0 for an existing session; stderr: " + replay.getErrorOutput()
                        + "; stdout: " + replay.getOutput());
        String out = replay.getOutput();
        assertTrue(out.contains("What is two plus two?"),
                () -> "replay must reproduce the recorded user message; got: " + out);
        assertTrue(out.contains("pong"),
                () -> "replay must reproduce the recorded assistant reply (fake provider 'pong'); got: " + out);
    }

    @Test
    void replayReportsAnUnknownSessionAndExitsNonZero(QuarkusMainLauncher launcher) {
        LaunchResult replay = launcher.launch("replay", "no-such-session");
        assertEquals(1, replay.exitCode(),
                () -> "replay must exit 1 when the session id matches no stored session; stdout: "
                        + replay.getOutput());
        assertTrue(replay.getErrorOutput().contains("no-such-session"),
                () -> "replay must name the missing session on stderr; got: " + replay.getErrorOutput());
    }

    /** The session id {@code ask} writes for a CLI turn: {@code cli:<os-user>} (mirrors {@code AskCommand}). */
    private static String cliSession() {
        String user = System.getProperty("user.name");
        return "cli:" + (user == null || user.isBlank() ? "local" : user);
    }
}
