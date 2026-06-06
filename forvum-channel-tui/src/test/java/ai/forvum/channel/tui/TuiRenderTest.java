package ai.forvum.channel.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.forvum.core.InvocationStatus;
import ai.forvum.core.ModelRef;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.core.event.FallbackTriggered;
import ai.forvum.core.event.TokenDelta;
import ai.forvum.core.event.ToolInvoked;
import ai.forvum.core.event.ToolResult;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code TuiChannel.render} maps each {@code AgentEvent} to the text the terminal user sees. v0.1
 * (streaming Option B) surfaces the {@link TokenDelta} reply and an {@link ErrorEvent}'s message; the
 * terminal {@link Done} and the tool-lifecycle events render to an empty string (printed as nothing).
 * Exhaustive over the sealed {@code AgentEvent}, mirroring the web channel's render contract.
 */
class TuiRenderTest {

    private static final Instant T = Instant.EPOCH;
    private static final ModelRef MODEL = ModelRef.parse("fake:m");

    @Test
    void tokenDeltaRendersItsText() {
        assertEquals("hi", TuiChannel.render(new TokenDelta(T, "hi", MODEL)));
    }

    @Test
    void errorEventRendersItsMessage() {
        ErrorEvent error =
                ErrorEvent.from(T, UUID.randomUUID(), "turn_failed", "boom", new RuntimeException("boom"));
        assertEquals("boom", TuiChannel.render(error));
    }

    @Test
    void doneRendersEmpty() {
        assertEquals("", TuiChannel.render(new Done(T, UUID.randomUUID(), "reply")));
    }

    @Test
    void toolInvokedRendersEmpty() {
        assertEquals("", TuiChannel.render(new ToolInvoked(T, 1L, "fs.read", "{}")));
    }

    @Test
    void toolResultRendersEmpty() {
        assertEquals("", TuiChannel.render(new ToolResult(T, 1L, "ok", InvocationStatus.OK, 5L)));
    }

    @Test
    void fallbackTriggeredRendersEmpty() {
        assertEquals("",
                TuiChannel.render(new FallbackTriggered(T, MODEL, ModelRef.parse("fake:n"), "rate_limit")));
    }
}
