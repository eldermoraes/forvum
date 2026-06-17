package ai.forvum.tools.browser;

import ai.forvum.tools.browser.dto.CdpMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocketClient;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;

import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The CDP client endpoint over {@code quarkus-websockets-next} CLIENT mode (the native-clean transport the
 * P2-CH discord readiness spike compiled + booted). Connected by {@link CdpSession} to the page-target
 * {@code ws://localhost:9222/devtools/page/<id>} URL discovered from Chrome, it CORRELATES inbound frames:
 *
 * <ul>
 *   <li>A command RESPONSE ({@code { "id": <n>, "result": {...} }} or {@code { ..., "error": {...} }}) is
 *       matched to its outbound command via {@link #pending} (keyed by the monotonic {@code CdpProtocol}
 *       id) and completes that command's {@link CompletableFuture}.</li>
 *   <li>An unsolicited EVENT ({@code { "method": "...", "params": {...} }}) is forwarded to the single
 *       registered {@link #eventListener} (e.g. {@code CdpSession} awaiting {@code Page.loadEventFired}).</li>
 * </ul>
 *
 * <p><strong>Concurrency (CLAUDE.md §3.8).</strong> Inbound frames run {@code @RunOnVirtualThread} so
 * handling blocks on a virtual thread, never the event loop. The correlation map is a
 * {@link ConcurrentHashMap} of {@link CompletableFuture} — no {@code synchronized}. {@code @WebSocketClient}
 * endpoints are CDI beans; this one is the default {@code @Singleton} (one CDP connection per process).
 */
@WebSocketClient(path = "/")
public class CdpEndpoint {

    private static final Logger LOG = Logger.getLogger(CdpEndpoint.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final CdpProtocol protocol = new CdpProtocol(mapper);

    /** Outstanding command ids → the future awaiting their response. Completed by {@link #onText}. */
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    /** The single event listener (set by {@link CdpSession}); routed every unsolicited event. */
    private volatile Consumer<CdpMessage> eventListener;

    /** The shared protocol (id allocator + builders) so {@link CdpSession} and the endpoint agree on ids. */
    CdpProtocol protocol() {
        return protocol;
    }

    /** Register the (single) event listener; {@code null} clears it on close. */
    void setEventListener(Consumer<CdpMessage> listener) {
        this.eventListener = listener;
    }

    /**
     * Register a pending command id and return its future (resolved by {@link #onText} when the matching
     * response arrives, or completed exceptionally on close / timeout by {@link CdpSession}).
     */
    CompletableFuture<JsonNode> awaitResponse(long id) {
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        return future;
    }

    /** Drop a pending id (a timed-out / abandoned command), so the map does not leak. */
    void forget(long id) {
        pending.remove(id);
    }

    @OnOpen
    void onOpen(WebSocketClientConnection connection) {
        LOG.debug("CDP connection opened.");
    }

    /**
     * Handle one inbound CDP text frame on a virtual thread: parse → either complete the pending command
     * future (a response) or forward to the event listener (an event). A parse/handle failure for one frame
     * is logged and swallowed so a single bad frame never kills the session.
     */
    @OnTextMessage
    @RunOnVirtualThread
    void onText(WebSocketClientConnection connection, String frame) {
        CdpMessage message;
        try {
            message = protocol.parse(frame);
        } catch (RuntimeException e) {
            LOG.warnf("CDP: dropping an unparseable frame (%s).", e.getMessage());
            return;
        }
        if (message.isResponse()) {
            CompletableFuture<JsonNode> future = pending.remove(message.id());
            if (future == null) {
                return; // a response to a forgotten/timed-out command
            }
            if (message.isError()) {
                JsonNode msg = message.error().get("message");
                future.completeExceptionally(new CdpException(
                        "CDP command failed: " + (msg == null ? message.error().toString() : msg.asText())));
            } else {
                future.complete(message.result());
            }
            return;
        }
        Consumer<CdpMessage> listener = eventListener;
        if (listener != null) {
            listener.accept(message);
        }
    }

    /** The connection closed — fail every still-pending command so awaiting callers do not hang. */
    @OnClose
    void onClose(WebSocketClientConnection connection) {
        LOG.debug("CDP connection closed; failing outstanding commands.");
        failAllPending(new CdpException("The CDP connection to Chrome closed."));
        eventListener = null;
    }

    /** Complete every outstanding command exceptionally (on close) and clear the map. */
    void failAllPending(CdpException cause) {
        pending.forEach((id, future) -> future.completeExceptionally(cause));
        pending.clear();
    }
}
