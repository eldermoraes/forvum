package ai.forvum.channel.whatsapp;

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
 * {@code MessageProcessor.render} maps each {@code AgentEvent} to the text the WhatsApp sender receives.
 * v0.1 (streaming Option B) surfaces the {@link TokenDelta} reply and an {@link ErrorEvent}'s message;
 * the terminal {@link Done} and the tool-lifecycle events render to an empty string (sent as nothing) —
 * crucially the terminal Done must NOT re-send the reply (a duplicate-message regression). Exhaustive
 * over the sealed {@code AgentEvent}, mirroring the Telegram/Signal/Matrix render tests.
 */
class WhatsAppRenderTest {

    private static final Instant T = Instant.EPOCH;
    private static final ModelRef MODEL = ModelRef.parse("fake:m");

    @Test
    void tokenDeltaRendersItsText() {
        assertEquals("hi", MessageProcessor.render(new TokenDelta(T, "hi", MODEL)));
    }

    @Test
    void errorEventRendersItsMessage() {
        ErrorEvent error =
                ErrorEvent.from(T, UUID.randomUUID(), "turn_failed", "boom", new RuntimeException("boom"));
        assertEquals("boom", MessageProcessor.render(error));
    }

    @Test
    void doneRendersEmpty() {
        assertEquals("", MessageProcessor.render(new Done(T, UUID.randomUUID(), "reply")),
                "the terminal Done must render to nothing (no duplicate reply)");
    }

    @Test
    void toolInvokedRendersEmpty() {
        assertEquals("", MessageProcessor.render(new ToolInvoked(T, 1L, "fs.read", "{}")));
    }

    @Test
    void toolResultRendersEmpty() {
        assertEquals("", MessageProcessor.render(new ToolResult(T, 1L, "ok", InvocationStatus.OK, 5L)));
    }

    @Test
    void fallbackTriggeredRendersEmpty() {
        assertEquals("",
                MessageProcessor.render(
                        new FallbackTriggered(T, MODEL, ModelRef.parse("fake:n"), "rate_limit")));
    }
}
