package ai.forvum.tools.browser;

import ai.forvum.tools.browser.dto.CdpCommand;
import ai.forvum.tools.browser.dto.CdpMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.quarkus.websockets.next.WebSocketConnector;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
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
 * <p>The per-page WS URL is server-supplied at runtime (the discord dynamic-{@code baseUri} pattern):
 * {@link CdpDiscovery} discovers it over HTTP, then {@code connectors.get().baseUri(URI.create(wsUrl))
 * .connectAndAwait()} dials it ({@code path="/"} on {@link CdpEndpoint} is a placeholder the full baseUri
 * overrides).
 *
 * <p><strong>Concurrency (CLAUDE.md §3.8).</strong> The connector is obtained per-connect from
 * {@code Instance.get()} (connectors are single-use, not thread-safe). The live connection is an
 * {@link AtomicReference}; response correlation is the endpoint's {@link java.util.concurrent.ConcurrentHashMap}
 * of {@link CompletableFuture}. The lazy-connect critical section is guarded by a {@link ReentrantLock}
 * (so two concurrent first-invokes do not open two sockets) — the only IO under it is the blocking dial,
 * which is acceptable here (a tool call is not an engine/channel hot path, and the alternative double-dial
 * is worse); commands run lock-free against the established connection.
 */
@ApplicationScoped
public class CdpSession implements CdpExecutor {

    private static final Logger LOG = Logger.getLogger(CdpSession.class);

    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    BrowserConfig config;

    /**
     * Connector factory for the {@link CdpEndpoint} client endpoint. A fresh connector per connect
     * ({@code Instance.get()}) is required — connectors are single-use and not thread-safe.
     */
    @Inject
    Instance<WebSocketConnector<CdpEndpoint>> connectors;

    /** The endpoint singleton (the same bean the connector drives) — holds the id→future correlation map. */
    @Inject
    CdpEndpoint endpoint;

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
            WebSocketClientConnection opened = dial(wsUrl);
            endpoint.setEventListener(this::onEvent);
            connection.set(opened);
            LOG.infof("CDP: attached to a Chrome page target.");
            return opened;
        } finally {
            connectLock.unlock();
        }
    }

    /** Dial one page-target WS URL (the blocking handshake). Package-private so a test can stub it. */
    WebSocketClientConnection dial(String wsUrl) {
        return connectors.get()
                .baseUri(URI.create(wsUrl))
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
        CdpProtocol protocol = endpoint.protocol();
        CompletableFuture<JsonNode> future = endpoint.awaitResponse(command.id());
        try {
            conn.sendTextAndAwait(protocol.encodeCommand(command));
            return future.get(config.read().commandTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            endpoint.forget(command.id());
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
            endpoint.forget(command.id());
            throw new CdpException("Interrupted awaiting CDP command '" + command.method() + "'.", e);
        }
    }

    /** The shared protocol (id allocator + command builders). */
    @Override
    public CdpProtocol protocol() {
        return endpoint.protocol();
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
