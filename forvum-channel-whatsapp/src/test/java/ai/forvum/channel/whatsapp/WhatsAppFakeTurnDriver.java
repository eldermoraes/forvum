package ai.forvum.channel.whatsapp;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.ModelRef;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.TokenDelta;
import ai.forvum.sdk.ChannelTurnDriver;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-module fake {@link ChannelTurnDriver}. The real driver ({@code TurnService}) lives in forvum-engine,
 * which a Layer-3 channel must not depend on, so the {@code IT} here cannot reach it; this fake emits one
 * {@link TokenDelta} echoing the inbound content then a terminal {@link Done}, and records every
 * dispatched {@link ChannelMessage} so a test can await that the webhook drove the turn (the processing
 * is async on a virtual thread). Live channel-to-engine wiring is a {@code *LiveTest @Tag("live")}
 * concern, default-off.
 */
@ApplicationScoped
public class WhatsAppFakeTurnDriver implements ChannelTurnDriver {

    static final ModelRef MODEL = ModelRef.parse("fake:test-model");

    private final CopyOnWriteArrayList<ChannelMessage> dispatched = new CopyOnWriteArrayList<>();

    @Override
    public void dispatch(ChannelMessage message, Consumer<AgentEvent> sink) {
        dispatched.add(message);
        Instant now = Instant.now();
        String reply = "echo:" + message.content();
        sink.accept(new TokenDelta(now, reply, MODEL));
        sink.accept(new Done(now, UUID.randomUUID(), reply));
    }

    /** A snapshot of the messages dispatched so far (method access for the CDI client-proxy reason). */
    public List<ChannelMessage> dispatched() {
        return List.copyOf(dispatched);
    }

    /** Clear the recorded dispatches between tests. */
    public void reset() {
        dispatched.clear();
    }
}
