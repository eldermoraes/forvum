package ai.forvum.channel.web;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.ModelRef;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.TokenDelta;
import ai.forvum.sdk.ChannelTurnDriver;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * In-module fake {@link ChannelTurnDriver}. The real driver ({@code TurnService}) lives in
 * forvum-engine, which a Layer-3 channel must not depend on, so {@code ChatSocketIT} cannot reach it;
 * this fake emits Option B's shape — one {@link TokenDelta} echoing the inbound content, then a
 * terminal {@link Done} — so the test exercises the WebSocket transport + render path in isolation. The
 * full channel-to-engine wiring is covered app-side by {@code WebScriptedTurnE2E}.
 */
@ApplicationScoped
public class FakeTurnDriver implements ChannelTurnDriver {

    static final ModelRef MODEL = ModelRef.parse("fake:test-model");

    @Override
    public void dispatch(ChannelMessage message, Consumer<AgentEvent> sink) {
        Instant now = Instant.now();
        String reply = "echo:" + message.content();
        sink.accept(new TokenDelta(now, reply, MODEL));
        sink.accept(new Done(now, UUID.randomUUID(), reply));
    }
}
