package ai.forvum.channel.telegram;

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
 * {@code UpdateProcessor.render} maps each {@code AgentEvent} to the text the Telegram user receives.
 * v0.1 (streaming Option B) surfaces the {@link TokenDelta} reply and an {@link ErrorEvent}'s message;
 * the terminal {@link Done} and the tool-lifecycle events render to an empty string (sent as nothing).
 * Exhaustive over the sealed {@code AgentEvent}, mirroring the web channel's render test.
 */
class TelegramRenderTest {

    private static final Instant T = Instant.EPOCH;
    private static final ModelRef MODEL = ModelRef.parse("fake:m");

    @Test
    void tokenDeltaRendersItsText() {
        assertEquals("hi", UpdateProcessor.render(new TokenDelta(T, "hi", MODEL)));
    }

    @Test
    void errorEventRendersItsMessage() {
        ErrorEvent error =
                ErrorEvent.from(T, UUID.randomUUID(), "turn_failed", "boom", new RuntimeException("boom"));
        assertEquals("boom", UpdateProcessor.render(error));
    }

    @Test
    void doneRendersEmpty() {
        assertEquals("", UpdateProcessor.render(new Done(T, UUID.randomUUID(), "reply")));
    }

    @Test
    void toolInvokedRendersEmpty() {
        assertEquals("", UpdateProcessor.render(new ToolInvoked(T, 1L, "fs.read", "{}")));
    }

    @Test
    void toolResultRendersEmpty() {
        assertEquals("", UpdateProcessor.render(new ToolResult(T, 1L, "ok", InvocationStatus.OK, 5L)));
    }

    @Test
    void fallbackTriggeredRendersEmpty() {
        assertEquals("",
                UpdateProcessor.render(
                        new FallbackTriggered(T, MODEL, ModelRef.parse("fake:n"), "rate_limit")));
    }
}
