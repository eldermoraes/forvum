package ai.forvum.channel.tui;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.ModelRef;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.TokenDelta;
import ai.forvum.sdk.ChannelTurnDriver;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The TUI REPL reads one line of stdin per turn, drives it through the injected {@code ChannelTurnDriver},
 * and streams each rendered {@code AgentEvent} to stdout via the supplied {@link TuiView} — the pipeable
 * path the Verify exercises with {@code forvum-app -Dforvum.no-ansi < input.txt} (plain view) and the
 * styled interactive path (ANSI view). Blank lines are skipped; end-of-input ends the loop and returns 0.
 */
class TuiChannelReplTest {

    private static final ModelRef MODEL = ModelRef.parse("fake:test-model");

    /** Records dispatched message contents and echoes each as Option B (one TokenDelta, then Done). */
    private static final class EchoDriver implements ChannelTurnDriver {
        final List<String> dispatched = new ArrayList<>();

        @Override
        public void dispatch(ChannelMessage message, Consumer<AgentEvent> sink) {
            dispatched.add(message.content());
            String reply = "echo:" + message.content();
            sink.accept(new TokenDelta(Instant.EPOCH, reply, MODEL));
            sink.accept(new Done(Instant.EPOCH, UUID.randomUUID(), reply));
        }
    }

    private static InputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(UTF_8));
    }

    private static PrintStream out(ByteArrayOutputStream sink) {
        return new PrintStream(sink, true, UTF_8);
    }

    @Test
    void plainReplStreamsRenderedReplyForEachLine() {
        TuiChannel channel = new TuiChannel();
        channel.turns = new EchoDriver();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = channel.repl(in("hi\nyo\n"), out(out), TuiView.plain());

        String printed = out.toString(UTF_8);
        assertTrue(printed.contains("echo:hi"), printed);
        assertTrue(printed.contains("echo:yo"), printed);
        assertEquals(0, code);
    }

    @Test
    void styledReplOutputContainsTheReplyText() {
        TuiChannel channel = new TuiChannel();
        channel.turns = new EchoDriver();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        channel.repl(in("hi\n"), out(out), TuiView.ansi());

        assertTrue(out.toString(UTF_8).contains("echo:hi"), out.toString(UTF_8));
    }

    @Test
    void dispatchesOneTurnPerNonBlankLineSkippingBlanks() {
        TuiChannel channel = new TuiChannel();
        EchoDriver driver = new EchoDriver();
        channel.turns = driver;

        channel.repl(in("\n   \nhi\n\nyo\n"), out(new ByteArrayOutputStream()), TuiView.plain());

        assertEquals(List.of("hi", "yo"), driver.dispatched);
    }

    @Test
    void nativeUserIdUsesUserNameOrFallsBackToLocalWhenBlank() {
        String original = System.getProperty("user.name");
        try {
            System.setProperty("user.name", "alice");
            assertEquals("alice", TuiChannel.nativeUserId());

            System.setProperty("user.name", "   ");
            assertEquals("local", TuiChannel.nativeUserId());

            System.clearProperty("user.name");
            assertEquals("local", TuiChannel.nativeUserId());
        } finally {
            if (original == null) {
                System.clearProperty("user.name");
            } else {
                System.setProperty("user.name", original);
            }
        }
    }
}
