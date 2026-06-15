package ai.forvum.channel.matrix;

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
 * {@code SyncProcessor.render} maps each {@code AgentEvent} to the text the Matrix user receives.
 * v0.1 (streaming Option B) surfaces the {@link TokenDelta} reply and an {@link ErrorEvent}'s message;
 * the terminal {@link Done} and the tool-lifecycle events render to an empty string (sent as nothing).
 * Exhaustive over the sealed {@code AgentEvent}, mirroring the Telegram/Discord/web render tests.
 */
class MatrixRenderTest {

    private static final Instant T = Instant.EPOCH;
    private static final ModelRef MODEL = ModelRef.parse("fake:m");

    @Test
    void tokenDeltaRendersItsText() {
        assertEquals("hi", SyncProcessor.render(new TokenDelta(T, "hi", MODEL)));
    }

    @Test
    void errorEventRendersItsMessage() {
        ErrorEvent error =
                ErrorEvent.from(T, UUID.randomUUID(), "turn_failed", "boom", new RuntimeException("boom"));
        assertEquals("boom", SyncProcessor.render(error));
    }

    @Test
    void doneRendersEmpty() {
        assertEquals("", SyncProcessor.render(new Done(T, UUID.randomUUID(), "reply")));
    }

    @Test
    void toolInvokedRendersEmpty() {
        assertEquals("", SyncProcessor.render(new ToolInvoked(T, 1L, "fs.read", "{}")));
    }

    @Test
    void toolResultRendersEmpty() {
        assertEquals("", SyncProcessor.render(new ToolResult(T, 1L, "ok", InvocationStatus.OK, 5L)));
    }

    @Test
    void fallbackTriggeredRendersEmpty() {
        assertEquals("",
                SyncProcessor.render(
                        new FallbackTriggered(T, MODEL, ModelRef.parse("fake:n"), "rate_limit")));
    }
}
