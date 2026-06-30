package ai.forvum.sdk;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.DeviceCredential;
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

    /**
     * Drive a single turn carrying the device credential the channel adapter authenticated the
     * connection with (#166). The engine ({@code TurnService}) overrides this to authenticate the
     * credential against the paired device (timing-safe token compare, channel bind, revocation) BEFORE
     * the responder runs and to intersect the device's {@code approvedScopes} into the turn's effective
     * scopes.
     *
     * <p>The default delegates to {@link #dispatch(ChannelMessage, Consumer)} <em>without</em> consuming
     * the credential, so the many channel-test fakes that implement only the two-arg method keep working
     * unchanged ({@link DeviceCredential#ABSENT} — the paired-by-existence / operator / local path).
     * A channel that authenticates a per-connection device (the Web channel) calls this overload; a
     * channel with no per-connection credential keeps calling the two-arg method.
     */
    default void dispatch(ChannelMessage message, DeviceCredential credential, Consumer<AgentEvent> sink) {
        dispatch(message, sink);
    }
}
