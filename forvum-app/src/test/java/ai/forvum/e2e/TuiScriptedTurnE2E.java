package ai.forvum.e2e;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.tui.TuiChannel;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * End-to-end across the whole app stack for the TUI channel: a scripted stdin line drives a real turn —
 * {@code TuiChannel.run()} (the binary's foreground entry, no-ANSI mode) → the injected SDK
 * {@code ChannelTurnDriver} (resolved to the engine's {@code TurnService} on the app classpath) → the
 * agent runtime → the in-process {@code FakeModelProvider} — and the rendered reply ({@code "pong"})
 * streams back to stdout. The TUI counterpart to {@code WebScriptedTurnE2E}: the M15 Verify's
 * "pipe scripted stdin, assert the rendered output contains the assistant reply" /
 * "{@code forvum-app -Dforvum.no-ansi < input.txt} works identically", exercised in-process by
 * redirecting {@code System.in}/{@code System.out}.
 */
@QuarkusTest
@TestProfile(TuiScriptedTurnE2E.FakeBackedTuiHomeProfile.class)
class TuiScriptedTurnE2E {

    @Inject
    TuiChannel tui;

    @Test
    void aPipedStdinLineDrivesARealTurnAndStreamsTheReplyToStdout() {
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        int exitCode;
        try {
            System.setIn(new ByteArrayInputStream("hello\n".getBytes(UTF_8)));
            System.setOut(new PrintStream(captured, true, UTF_8));
            exitCode = tui.run(); // no-ansi (set by the profile) → plain view, reads piped stdin to EOF
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }

        assertEquals(0, exitCode, "the REPL returns 0 at end of input");
        String out = captured.toString(UTF_8);
        assertTrue(out.contains("pong"),
                () -> "the engine drove the turn end-to-end through the in-process fake model; got: " + out);
    }

    /**
     * Seeds {@code main} pinned to the in-process {@code fake} provider and an enabled {@code tui.json}, and
     * forces no-ANSI so the REPL stays on the plain, pipeable path (raw stdout to the test JVM's
     * redirected/non-TTY stream).
     */
    public static class FakeBackedTuiHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-tui-e2e-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");
                Path channels = Files.createDirectories(home.resolve("channels"));
                Files.writeString(channels.resolve("tui.json"), "{ \"enabled\": true }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", HOME.toString(), "forvum.no-ansi", "true");
        }
    }
}
