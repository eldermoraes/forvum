package ai.forvum.channel.web;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.DeviceCredential;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.core.event.FallbackTriggered;
import ai.forvum.core.event.TokenDelta;
import ai.forvum.core.event.ToolInvoked;
import ai.forvum.core.event.ToolResult;
import ai.forvum.sdk.ChannelTurnDriver;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.websockets.next.HandshakeRequest;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;

import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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

    /**
     * The role {@code OperatorAuthMechanism} (#166) mints for a paired-device token. A string contract
     * shared with that mechanism (a Layer-3 channel cannot depend on {@code forvum-app}).
     */
    static final String DEVICE_ROLE = "device";

    @Inject
    WebSocketConnection connection;

    @Inject
    ChannelTurnDriver turns;

    /**
     * The handshake-authenticated principal (#165/#166). The {@code /ws/chat} HTTP policy admits the
     * {@code operator} (the host) or a {@code device} (a paired device that presented its own token), so
     * by the time a frame arrives this is one of those — never anonymous.
     */
    @Inject
    SecurityIdentity identity;

    @OnTextMessage
    @RunOnVirtualThread
    public void onMessage(String userText) {
        ChannelMessage message =
                new ChannelMessage(CHANNEL_ID, connection.id(), userText, Instant.now());
        turns.dispatch(message, credentialFor(identity, connection.handshakeRequest()), event -> {
            String rendered = render(event);
            if (rendered != null && !rendered.isEmpty()) {
                connection.sendTextAndAwait(rendered);
            }
        });
    }

    /**
     * The device credential to carry into the turn (#166). When the connection authenticated AS A DEVICE
     * (the mechanism minted the {@link #DEVICE_ROLE}, principal = the device id), propagate a
     * {@link DeviceCredential} whose token is the one presented at the handshake, so the engine
     * re-authenticates it and intersects the device's {@code approvedScopes}. The operator/host (#165) is
     * not a paired device, so it propagates {@link DeviceCredential#ABSENT} — keeping its full scopes.
     * Package-private + static so it is unit-testable without a live socket.
     */
    static DeviceCredential credentialFor(SecurityIdentity identity, HandshakeRequest handshake) {
        if (identity == null || identity.isAnonymous() || !identity.hasRole(DEVICE_ROLE)) {
            return DeviceCredential.ABSENT;
        }
        return new DeviceCredential(identity.getPrincipal().getName(), accessToken(handshake));
    }

    /**
     * The token the connection presented at the handshake: the {@code Authorization: Bearer} header, else
     * the {@code ?access_token=} query parameter (a browser WebSocket cannot set a header). Mirrors
     * {@code OperatorAuthMechanism}'s extraction so the engine re-validates the same secret.
     */
    static String accessToken(HandshakeRequest handshake) {
        String authorization = handshake.header("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            String token = authorization.substring("Bearer ".length()).strip();
            if (!token.isEmpty()) {
                return token;
            }
        }
        String query = handshake.query();
        if (query != null && !query.isEmpty()) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "access_token".equals(pair.substring(0, eq))) {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
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
