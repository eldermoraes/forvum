package ai.forvum.sdk;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;

import java.util.function.Consumer;

/**
 * The shared turn-driving contract every channel (web, tui, telegram) calls to run one turn
 * (ULTRAPLAN section 5.3 / 5.5). A channel is self-driving — its own inbound surface (a
 * {@code @WebSocket} callback, a stdin REPL, a long-poll loop) hands the engine a {@link ChannelMessage}
 * and consumes the turn as a stream of {@link AgentEvent} through the supplied {@code sink}.
 *
 * <p>The single implementation lives in {@code forvum-engine} (the {@code TurnService} facade);
 * promoting the contract to the SDK lets a Layer-3 channel depend only on {@code forvum-sdk} plus the
 * Layer-0 {@code forvum-core} types the contract re-exposes ({@link ChannelMessage} / {@link AgentEvent}),
 * never on the engine — preserving the channel-deps &sube; {forvum-sdk, forvum-core} invariant
 * (CLAUDE.md section 3 / 12). It is a plain (non-sealed) interface: channels are consumers, not
 * implementors, so there is no closed implementor set to seal.
 *
 * <p>The outbound type is a JDK {@link Consumer} of {@link AgentEvent} — never a Mutiny {@code Multi}
 * ({@code forvum-sdk} is Quarkus/Mutiny-free; section 3.8). v0.1 emits one {@code TokenDelta} then a
 * terminal {@code Done} (streaming Option B); true per-token streaming is a later non-breaking upgrade.
 */
public interface ChannelTurnDriver {

    /**
     * Drive a single turn for {@code message}, rendering its {@link AgentEvent} stream to {@code sink}.
     * The sink is invoked synchronously on the caller's thread, once per emitted event, in order.
     */
    void dispatch(ChannelMessage message, Consumer<AgentEvent> sink);
}
