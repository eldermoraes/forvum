package ai.forvum.channel.voice;

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
 * Every arm of {@link VoicePipeline#render}: the {@link TokenDelta} reply text and an {@link ErrorEvent}
 * message produce text the channel synthesizes; the terminal {@link Done} and the tool-lifecycle /
 * fallback events render to nothing (streaming Option B). Byte-identical to the other channels' renderers
 * (the exhaustive default-less switch makes a new {@code AgentEvent} arm a compile error in every
 * channel).
 */
class VoiceRenderTest {

    private static final Instant NOW = Instant.EPOCH;
    private static final ModelRef MODEL = ModelRef.parse("fake:m");

    @Test
    void tokenDeltaRendersItsText() {
        assertEquals("hello", VoicePipeline.render(new TokenDelta(NOW, "hello", MODEL)));
    }

    @Test
    void errorEventRendersItsMessage() {
        assertEquals("boom",
                VoicePipeline.render(ErrorEvent.from(NOW, UUID.randomUUID(), "code", "boom", null)));
    }

    @Test
    void terminalAndToolEventsRenderToNothing() {
        assertEquals("", VoicePipeline.render(new Done(NOW, UUID.randomUUID(), "final")));
        assertEquals("", VoicePipeline.render(new ToolInvoked(NOW, 1L, "fs.read", "{}")));
        assertEquals("",
                VoicePipeline.render(new ToolResult(NOW, 1L, "ok", InvocationStatus.OK, 3L)));
        assertEquals("", VoicePipeline.render(
                new FallbackTriggered(NOW, MODEL, ModelRef.parse("fake:n"), "reason")));
    }
}
