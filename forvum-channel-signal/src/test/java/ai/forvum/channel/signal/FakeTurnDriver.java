package ai.forvum.channel.signal;

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
 * In-module fake {@link ChannelTurnDriver}. The real driver ({@code TurnService}) lives in
 * forvum-engine, which a Layer-3 channel must not depend on, so an {@code IT} here cannot reach it; this
 * fake emits Option B's shape — one {@link TokenDelta} echoing the inbound content, then a terminal
 * {@link Done} — and records every dispatched {@link ChannelMessage} so the test can assert that the
 * mapping ran (or, for a refused sender, that no turn was dispatched). Live channel-to-engine wiring
 * against a real signal-cli daemon is a {@code *LiveTest @Tag("live")} concern, default-off.
 */
@ApplicationScoped
public class FakeTurnDriver implements ChannelTurnDriver {

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

    /**
     * The messages dispatched so far. Accessed via a METHOD (not a field) because this is an
     * {@code @ApplicationScoped} bean: a test injects a CDI client proxy, and direct field access reads
     * the proxy's own empty field rather than delegating to the contextual instance — only method calls
     * are delegated. Returns a snapshot.
     */
    public List<ChannelMessage> dispatched() {
        return List.copyOf(dispatched);
    }

    /** Clear the recorded dispatches between tests (a method, for the same proxy-delegation reason). */
    public void reset() {
        dispatched.clear();
    }
}
