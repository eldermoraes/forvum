package ai.forvum.tools.browser;

import ai.forvum.tools.browser.dto.CdpMessage;

import com.fasterxml.jackson.databind.JsonNode;

import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Pure (socket-free) Chrome DevTools Protocol (CDP) inbound-frame router: the response-correlation logic
 * that previously lived in a {@code @WebSocketClient} endpoint, lifted into a plain object so it carries no
 * declared WebSocket path (no {@code @WebSocketClient(path = "/")} to collide with the discord gateway
 * endpoint on the assembled {@code forvum-app} classpath — the bug this refactor fixes) and is unit-testable
 * with no live Chrome. {@link CdpSession} feeds every raw text frame from the
 * {@link io.quarkus.websockets.next.BasicWebSocketConnector} {@code onTextMessage} callback into
 * {@link #onFrame(String)}:
 *
 * <ul>
 *   <li>A command RESPONSE ({@code { "id": <n>, "result": {...} }} or {@code { ..., "error": {...} }}) is
 *       matched to its outbound command via {@link #pending} (keyed by the monotonic {@link CdpProtocol}
 *       id) and completes that command's {@link CompletableFuture}.</li>
 *   <li>An unsolicited EVENT ({@code { "method": "...", "params": {...} }}) is forwarded to the single
 *       registered {@link #eventListener} (e.g. {@link CdpSession} buffering {@code Page.loadEventFired}).</li>
 * </ul>
 *
 * <p><strong>Concurrency (CLAUDE.md §3.8).</strong> The correlation map is a {@link ConcurrentHashMap} of
 * {@link CompletableFuture}; the listener is {@code volatile}. No {@code synchronized}. {@link #onFrame} is
 * invoked on the connector's virtual-thread message callback (sending blocks on a virtual thread).
 */
public final class CdpFrameRouter {

    private static final Logger LOG = Logger.getLogger(CdpFrameRouter.class);

    private final CdpProtocol protocol;

    /** Outstanding command ids → the future awaiting their response. Completed by {@link #onFrame}. */
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    /** The single event listener (set by {@link CdpSession}); routed every unsolicited event. */
    private volatile Consumer<CdpMessage> eventListener;

    public CdpFrameRouter(CdpProtocol protocol) {
        this.protocol = protocol;
    }

    /** Register the (single) event listener; {@code null} clears it on close. */
    void setEventListener(Consumer<CdpMessage> listener) {
        this.eventListener = listener;
    }

    /**
     * Register a pending command id and return its future (resolved by {@link #onFrame} when the matching
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

    /**
     * Handle one inbound CDP text frame: parse → either complete the pending command future (a response) or
     * forward to the event listener (an event). A parse/handle failure for one frame is logged and swallowed
     * so a single bad frame never kills the session.
     */
    void onFrame(String frame) {
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
    void onClose() {
        LOG.debug("CDP connection closed; failing outstanding commands.");
        failAllPending(new CdpException("The CDP connection to Chrome closed."));
        eventListener = null;
    }

    /** Complete every outstanding command exceptionally (on close) and clear the map. */
    void failAllPending(CdpException cause) {
        pending.forEach((id, future) -> future.completeExceptionally(cause));
        pending.clear();
    }

    /** The current event listener — for tests only. */
    Consumer<CdpMessage> eventListenerForTest() {
        return eventListener;
    }
}
