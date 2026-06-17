package ai.forvum.tools.browser;

import ai.forvum.tools.browser.dto.CdpCommand;
import ai.forvum.tools.browser.dto.CdpMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.websockets.next.BasicWebSocketConnector;
import io.quarkus.websockets.next.WebSocketClientConnection;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The browser tool's CDP lifecycle (P2-1, #26): LAZILY dials the operator-launched Chrome's page-target
 * WebSocket on the first tool invoke (NO {@code @Startup} network work — the [M14] graceful-absence
 * contract: an absent Chrome surfaces as a returned error string from {@code invoke}, never a boot
 * failure), holds the live connection in an {@link AtomicReference}, sends a {@link CdpCommand} and awaits
 * its response with a bounded timeout, and on shutdown closes the connection.
 *
 * <p><strong>Transport (the [M16] {@code BasicWebSocketConnector} fix).</strong> The per-page WS URL is
 * discovered at runtime ({@link CdpDiscovery} over HTTP) and dialed with
 * {@link BasicWebSocketConnector#create()} passing the FULL discovered URL as the {@code baseUri} — there is
 * NO {@code @WebSocketClient} endpoint class and NO declared path, so it cannot collide with the discord
 * gateway endpoint's {@code @WebSocketClient(path = "/")} on the assembled {@code forvum-app} classpath, and
 * the typed-connector path-concatenation trap (the [M16] lesson) does not apply. Inbound frames flow to the
 * socket-free {@link CdpFrameRouter} (response correlation + event forwarding); outbound commands are
 * encoded by the shared {@link CdpProtocol}.
 *
 * <p><strong>Concurrency (CLAUDE.md §3.8).</strong> The {@code onTextMessage} callback runs on a virtual
 * thread ({@link BasicWebSocketConnector.ExecutionModel#VIRTUAL_THREAD}), so frame handling blocks on a
 * virtual thread, never the event loop. The live connection is an {@link AtomicReference}; response
 * correlation is the router's {@link java.util.concurrent.ConcurrentHashMap} of {@link CompletableFuture} —
 * no {@code synchronized}. The lazy-connect critical section is guarded by a {@link ReentrantLock} (so two
 * concurrent first-invokes do not open two sockets) — the only IO under it is the blocking dial, which is
 * acceptable here (a tool call is not an engine/channel hot path, and the alternative double-dial is worse);
 * commands run lock-free against the established connection.
 */
@ApplicationScoped
public class CdpSession implements CdpExecutor {

    private static final Logger LOG = Logger.getLogger(CdpSession.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final CdpProtocol protocol = new CdpProtocol(mapper);
    private final CdpFrameRouter router = new CdpFrameRouter(protocol);

    @Inject
    BrowserConfig config;

    /** A factory for the discovery HTTP seam, so the connect-timeout is applied. Overridable in tests. */
    CdpHttpFactory httpFactory = JdkCdpHttp::new;

    /** Builds a {@link CdpHttp} for a given connect timeout (the production seam is {@link JdkCdpHttp}). */
    @FunctionalInterface
    interface CdpHttpFactory {
        CdpHttp create(int connectTimeoutMs);
    }

    private final AtomicReference<WebSocketClientConnection> connection = new AtomicReference<>();
    private final ReentrantLock connectLock = new ReentrantLock();

    /** Recent {@code Page.loadEventFired} events, drained by {@link #waitForLoad}. */
    private final ConcurrentLinkedQueue<CdpMessage> loadEvents = new ConcurrentLinkedQueue<>();

    /**
     * Ensure a live CDP connection, opening one (discovery + dial) on the first call. Throws
     * {@link CdpException} when Chrome is unreachable / exposes no target — the caller turns it into a
     * model-facing error string.
     */
    WebSocketClientConnection ensureConnected() {
        WebSocketClientConnection live = connection.get();
        if (live != null && live.isOpen()) {
            return live;
        }
        connectLock.lock();
        try {
            live = connection.get();
            if (live != null && live.isOpen()) {
                return live;
            }
            BrowserConfig.Spec spec = config.read();
            if (!spec.enabled()) {
                throw new CdpException(
                        "The browser tool is disabled. Enable it in $FORVUM_HOME/tools/browser.json "
                      + "(\"enabled\": true) and start Chrome with --remote-debugging-port=9222.");
            }
            String wsUrl = new CdpDiscovery(httpFactory.create(spec.connectTimeoutMs()), mapper)
                    .pageWebSocketUrl(spec.debugUrl());
            router.setEventListener(this::onEvent);
            WebSocketClientConnection opened = dial(wsUrl);
            connection.set(opened);
            LOG.infof("CDP: attached to a Chrome page target.");
            return opened;
        } finally {
            connectLock.unlock();
        }
    }

    /**
     * Dial one page-target WS URL (the blocking handshake) via {@link BasicWebSocketConnector} — no endpoint
     * class, no {@code @WebSocketClient} declared path (the [M16] fix), so no collision with the discord
     * gateway endpoint's {@code path = "/"}. Inbound frames and the close event are routed to the
     * socket-free {@link CdpFrameRouter}; the message callback runs on a virtual thread (§3.8).
     *
     * <p>The discovered URL ({@code ws://host:port/devtools/page/<id>}) is split into an authority-only
     * {@code baseUri} and an explicit {@code path}. This is NOT cosmetic: the connector's default path is
     * {@code "/"}, and its {@code mergePath(baseUri.getPath(), this.path)} of a path-bearing baseUri against
     * that default APPENDS a trailing slash ({@code /devtools/page/<id>/}), which Chrome's CDP endpoint
     * rejects with HTTP 500. Passing the path explicitly against an empty baseUri path keeps the exact
     * {@code /devtools/page/<id>} the upgrade requires. Package-private so a test can stub it.
     */
    WebSocketClientConnection dial(String wsUrl) {
        URI uri = URI.create(wsUrl);
        URI authority = URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
        return BasicWebSocketConnector.create()
                .baseUri(authority)
                .path(uri.getRawPath())
                .executionModel(BasicWebSocketConnector.ExecutionModel.VIRTUAL_THREAD)
                // Suppress the Origin header Vert.x sends by default: Chrome's CDP endpoint REJECTS (HTTP
                // 500) an upgrade whose Origin is not in its --remote-allow-origins allow-list, and a real
                // CDP client connects with NO Origin. Suppressing it lets the operator launch a plain
                // `chrome --remote-debugging-port=<n>` without also passing --remote-allow-origins.
                .customizeOptions((connectOptions, clientOptions) ->
                        connectOptions.setAllowOriginHeader(false))
                .onTextMessage((connection, frame) -> router.onFrame(frame))
                .onClose((connection, reason) -> router.onClose())
                .connectAndAwait();
    }

    /** Buffer load events for {@link #waitForLoad}; ignore everything else. */
    private void onEvent(CdpMessage event) {
        if ("Page.loadEventFired".equals(event.method())) {
            loadEvents.add(event);
        }
    }

    /**
     * Send {@code command} on the live connection and await its response (a JSON {@code result} node)
     * within the configured command timeout. Throws {@link CdpException} on a CDP error response, a
     * timeout, or a closed connection.
     */
    @Override
    public JsonNode send(CdpCommand command) {
        WebSocketClientConnection conn = ensureConnected();
        CompletableFuture<JsonNode> future = router.awaitResponse(command.id());
        try {
            conn.sendTextAndAwait(protocol.encodeCommand(command));
            return future.get(config.read().commandTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            router.forget(command.id());
            throw new CdpException("CDP command '" + command.method() + "' timed out.", e);
        } catch (CompletionException | java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof CdpException cdp) {
                throw cdp;
            }
            throw new CdpException("CDP command '" + command.method() + "' failed: " + cause.getMessage(),
                    cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            router.forget(command.id());
            throw new CdpException("Interrupted awaiting CDP command '" + command.method() + "'.", e);
        } catch (RuntimeException e) {
            // Mutiny's sendTextAndAwait rethrows a socket-write failure as a RAW RuntimeException (not
            // wrapped in CompletionException) — e.g. the connection dropped between ensureConnected() and
            // the write. The pending future was already registered, so forget it (no map leak) and surface
            // it as a CdpException so invoke() relays it gracefully instead of it escaping as a generic
            // turn error. (A CompletionException — itself a RuntimeException — is handled by the arm above.)
            router.forget(command.id());
            if (e instanceof CdpException cdp) {
                throw cdp;
            }
            throw new CdpException("CDP command '" + command.method() + "' failed: " + e.getMessage(), e);
        }
    }

    /** The shared protocol (id allocator + command builders). */
    @Override
    public CdpProtocol protocol() {
        return protocol;
    }

    /** Drain any buffered {@code Page.loadEventFired} events (consumed by a {@code wait} that armed them). */
    @Override
    public void clearLoadEvents() {
        loadEvents.clear();
    }

    /** Whether at least one {@code Page.loadEventFired} has arrived since the last {@link #clearLoadEvents}. */
    @Override
    public boolean loadEventSeen() {
        return !loadEvents.isEmpty();
    }

    void onStop(@Observes ShutdownEvent event) {
        WebSocketClientConnection conn = connection.getAndSet(null);
        if (conn != null) {
            try {
                conn.closeAndAwait();
            } catch (RuntimeException e) {
                LOG.warnf("CDP: error closing the connection on shutdown (%s).", e.getMessage());
            }
        }
    }
}
