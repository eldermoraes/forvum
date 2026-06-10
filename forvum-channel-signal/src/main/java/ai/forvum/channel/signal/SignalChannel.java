package ai.forvum.channel.signal;

import ai.forvum.channel.signal.SignalChannelConfig.Spec;
import ai.forvum.channel.signal.SignalEvents.SseEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * The Signal channel's inbound surface (P2-CH, connect-only): a worker on a virtual thread that
 * consumes the operator-run signal-cli daemon's SSE event stream ({@code GET {baseUrl}/api/v1/events}),
 * classifies each event via {@link SignalEvents}, and hands direct text messages to
 * {@link EnvelopeProcessor}, repeating until shutdown. Per CLAUDE.md §3.8 the reader is HAND-ROLLED
 * blocking IO on the JDK {@link HttpClient} ({@code BodyHandlers.ofLines()}) — no Mutiny/reactive SSE
 * client; {@code @RunOnVirtualThread} applies only to externally-invoked callbacks, so a loop this bean
 * starts itself is placed on a virtual thread via {@link Executors#newVirtualThreadPerTaskExecutor()}.
 *
 * <p><strong>Connect-only.</strong> Forvum never spawns or installs signal-cli (see
 * {@link ai.forvum.channel.signal the package overview} for the daemon command line); daemon
 * spawn/install is a documented follow-up.
 *
 * <p><strong>Self-healing reconnect.</strong> The daemon closing the stream (EOF) and any
 * IO/parse-transport failure both re-enter the connect loop after an exponential backoff
 * ({@link Backoff}: 1 s, 2 s, 4 s … capped at 60 s), {@linkplain Backoff#reset() reset} on the first
 * complete event of a connection — so a daemon restart self-heals without a reconnect storm. A
 * deliberate {@link ShutdownEvent} ({@code running == false}) never reconnects.
 *
 * <p><strong>Absent config → warn + no-op.</strong> If {@code channels/signal.json} is absent, the
 * channel is disabled, or {@code baseUrl}/{@code account} is missing, the loop is NOT started and the
 * bean logs and returns — it never throws and never blocks. This keeps the CI native no-config boot (no
 * {@code ~/.forvum/}) graceful, the same contract the M4 watcher and the other channels honor.
 */
@ApplicationScoped
public class SignalChannel {

    private static final Logger LOG = Logger.getLogger(SignalChannel.class);

    /** The daemon's SSE event-stream path (mirrors OpenClaw's signal client). */
    static final String EVENTS_PATH = "/api/v1/events";

    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    @RestClient
    SignalRpcApi api;

    @Inject
    SignalChannelConfig config;

    @Inject
    EnvelopeProcessor processor;

    /** Package-private so tests can start/stop a deterministic cycle and assert inert boots. */
    volatile boolean running;
    /** Package-private so the boot test can assert no worker was started on an inert boot. */
    ExecutorService worker;
    /** The reconnect backoff schedule; package-private so a lifecycle test can shrink the delays. */
    Backoff backoff = new Backoff();
    /** Sleep seam (default {@link Thread#sleep}); a test substitutes a no-op/recording sleeper. */
    Sleeper sleeper = Thread::sleep;

    /** An interruptible sleep, abstracted so a reconnect test asserts the backoff without real waits. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    void onStart(@Observes StartupEvent event) {
        Spec spec = config.read();
        if (!spec.enabled()) {
            LOG.info("Signal channel disabled (no channels/signal.json, or \"enabled\": false); "
                    + "not connecting to the signal-cli daemon.");
            return;
        }
        if (spec.baseUrl().isEmpty() || spec.account().isEmpty()) {
            LOG.warn("Signal channel enabled but baseUrl/account missing in channels/signal.json; not "
                    + "connecting. Set \"baseUrl\" (the operator-run signal-cli HTTP daemon) and "
                    + "\"account\" to activate the channel.");
            return;
        }
        String baseUrl = normalizeBaseUrl(spec.baseUrl().get());
        String account = spec.account().get();
        running = true;
        worker = Executors.newVirtualThreadPerTaskExecutor();
        worker.submit(() -> eventLoop(api, baseUrl, account));
        LOG.infof("Signal channel started: streaming %s from the operator-run signal-cli daemon on a "
                + "virtual thread (connect-only; Forvum does not manage the daemon).", EVENTS_PATH);
    }

    void onStop(@Observes ShutdownEvent event) {
        running = false;
        if (worker != null) {
            worker.shutdownNow();
        }
    }

    /**
     * Whether the stream worker is running. A METHOD (not field access) so a {@code @QuarkusTest} can
     * assert the inert no-config boot through the CDI client proxy (field reads on a proxy see the
     * proxy's own field, never the contextual instance's).
     */
    boolean isStreaming() {
        return running;
    }

    /**
     * The connect-consume-reconnect loop: open the SSE stream, consume it until EOF or failure, then
     * back off and reconnect, repeating until {@link #onStop}. The daemon closing the stream is routine
     * (signal-cli restarts, network blips), so EOF is an INFO; a failure is a WARN with the account
     * query redacted. The {@link SignalRpcApi} is a parameter (not the injected field) so a test can
     * drive the loop with a recording client; production passes the injected {@code @RestClient} bean.
     */
    void eventLoop(SignalRpcApi api, String baseUrl, String account) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        try {
            while (running) {
                try {
                    streamOnce(client, api, baseUrl, account);
                    if (running) {
                        LOG.info("Signal: event stream ended (daemon closed it); reconnecting after "
                                + "back-off.");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return; // shutdownNow during connect/read — exit quietly.
                } catch (IOException | RuntimeException e) {
                    if (!running) {
                        return;
                    }
                    LOG.warnf("Signal: event stream failed (%s); reconnecting after back-off.",
                            redact(e.getMessage()));
                }
                if (!running) {
                    return;
                }
                try {
                    sleeper.sleep(backoff.nextDelayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return; // shutdownNow during the back-off — exit quietly.
                }
            }
        } finally {
            client.shutdownNow();
        }
    }

    /**
     * Open the SSE stream once and consume it until the daemon closes it. A non-200 status is an
     * {@link IOException} (the caller backs off and retries). Package-private so a test can drive one
     * connection against an in-test HTTP server.
     */
    void streamOnce(HttpClient client, SignalRpcApi api, String baseUrl, String account)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(eventsUri(baseUrl, account)))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<Stream<String>> response =
                client.send(request, HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException(
                        "Signal events stream returned HTTP " + response.statusCode() + ".");
            }
            consume(lines, api, baseUrl, account);
        }
    }

    /**
     * Consume one connection's lines: assemble SSE events ({@link SseAccumulator}), classify each
     * ({@link SignalEvents#parse}), and dispatch text messages to {@link EnvelopeProcessor}. The
     * {@link Spec} is re-read per event so an operator's {@code allowedUserIds} edit takes effect
     * without a restart. Each event's dispatch is isolated in try/catch so one throwing event cannot
     * kill the stream (the M4 watcher lesson); the backoff resets on each COMPLETE event (comments /
     * keep-alives do not complete one), marking the connection healthy.
     */
    void consume(Stream<String> lines, SignalRpcApi api, String baseUrl, String account) {
        SseAccumulator accumulator = new SseAccumulator();
        lines.forEach(line -> {
            SseEvent event = accumulator.feed(line);
            if (event == null) {
                return;
            }
            backoff.reset();
            try {
                switch (SignalEvents.parse(mapper, event)) {
                    case SignalEvents.TextMessage text ->
                            processor.process(text, config.read(), api, baseUrl, account);
                    case SignalEvents.Ignored ignored ->
                            LOG.debugf("Signal: ignoring event (%s).", ignored.reason());
                }
            } catch (RuntimeException e) {
                // One bad event (a config read failure, an unexpected processor error) must not kill
                // the stream; message content is never logged.
                LOG.warnf("Signal: failed to process an event (%s); continuing the stream.",
                        redact(e.getMessage()));
            }
        });
    }

    /**
     * The events-stream URI: {@code <baseUrl>/api/v1/events?account=<urlencoded>}. Package-private,
     * pure, and null/blank-account-safe (the query is omitted) for direct unit testing.
     */
    static String eventsUri(String baseUrl, String account) {
        String uri = normalizeBaseUrl(baseUrl) + EVENTS_PATH;
        if (account != null && !account.isBlank()) {
            uri += "?account=" + URLEncoder.encode(account, StandardCharsets.UTF_8);
        }
        return uri;
    }

    /**
     * Normalize the operator's {@code baseUrl}: trim, default a missing scheme to {@code http://} (the
     * daemon is a local plain-HTTP endpoint), and drop trailing slashes so path concatenation is exact.
     * Mirrors OpenClaw's signal client normalization.
     */
    static String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl.strip();
        if (!normalized.regionMatches(true, 0, "http://", 0, 7)
                && !normalized.regionMatches(true, 0, "https://", 0, 8)) {
            normalized = "http://" + normalized;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * Redact the {@code account} query value from a log-bound string. The Signal channel carries no
     * secret token (the daemon is local and unauthenticated), but an exception message can echo the
     * request URL whose {@code account} parameter is the operator's phone number — masked so it never
     * reaches the logs. Null-safe.
     */
    static String redact(String message) {
        if (message == null) {
            return null;
        }
        return message.replaceAll("(?i)account=[^&\\s]+", "account=<redacted>");
    }
}
