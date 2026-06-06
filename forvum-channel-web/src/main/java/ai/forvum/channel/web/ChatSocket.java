package ai.forvum.channel.web;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.core.event.FallbackTriggered;
import ai.forvum.core.event.TokenDelta;
import ai.forvum.core.event.ToolInvoked;
import ai.forvum.core.event.ToolResult;
import ai.forvum.sdk.ChannelTurnDriver;

import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;

import jakarta.inject.Inject;

import java.time.Instant;

/**
 * The Web channel's inbound surface (ULTRAPLAN section 5.3, the M16 Channel-Fleet anchor). A
 * WebSockets-Next endpoint at {@code /ws/chat} turns each inbound text frame into a
 * {@link ChannelMessage} and drives a turn through the SDK {@link ChannelTurnDriver} (the engine's
 * {@code TurnService} at runtime), streaming each rendered {@link AgentEvent} back over the same
 * connection.
 *
 * <p>{@code @RunOnVirtualThread}: the callback blocks twice — on the driver's single-shot turn and on
 * {@link WebSocketConnection#sendTextAndAwait} (which the API contract says "should only be called from
 * an executor thread"). Virtual threads are the sanctioned blocking model (section 3.8); returning a
 * Mutiny {@code Multi} where a VT suffices is a PR-reject. One connection is one conversation: the
 * session's native user id is {@link WebSocketConnection#id()}, stable for the lifetime of a browser
 * tab's socket.
 */
@WebSocket(path = "/ws/chat")
public class ChatSocket {

    /** Channel id stamped on every inbound {@link ChannelMessage}; matches the plugin extension id. */
    static final String CHANNEL_ID = "web";

    @Inject
    WebSocketConnection connection;

    @Inject
    ChannelTurnDriver turns;

    @OnTextMessage
    @RunOnVirtualThread
    public void onMessage(String userText) {
        ChannelMessage message =
                new ChannelMessage(CHANNEL_ID, connection.id(), userText, Instant.now());
        turns.dispatch(message, event -> {
            String rendered = render(event);
            if (rendered != null && !rendered.isEmpty()) {
                connection.sendTextAndAwait(rendered);
            }
        });
    }

    /**
     * Render an {@link AgentEvent} to the text frame the browser receives, or an empty string to send
     * nothing. Exhaustive over the sealed event type (no {@code default} branch). v0.1 (streaming
     * Option B) emits only {@link TokenDelta} (the reply) followed by a terminal {@link Done} — the
     * {@code Done} repeats the reply already carried by the {@code TokenDelta}, so it is skipped. The
     * tool-lifecycle events are not surfaced to the web UI yet; an {@link ErrorEvent} surfaces its
     * message so a failed turn is visible to the user (the engine's {@code TurnService} surfaces a
     * failed turn as a terminal {@code ErrorEvent}). Package-private so {@code ChatSocketRenderTest}
     * can cover every arm.
     */
    static String render(AgentEvent event) {
        return switch (event) {
            case TokenDelta delta -> delta.text();
            case ErrorEvent error -> error.message();
            case Done ignored -> "";
            case ToolInvoked ignored -> "";
            case ToolResult ignored -> "";
            case FallbackTriggered ignored -> "";
        };
    }
}
