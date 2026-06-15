package ai.forvum.channel.whatsapp;

import ai.forvum.channel.whatsapp.WhatsAppChannelConfig.Spec;
import ai.forvum.channel.whatsapp.WhatsAppEvents.InboundMessage;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.vertx.web.Route;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The WhatsApp channel's inbound surface (P2-CH): the Meta Cloud API webhook, served as two declarative
 * {@link Route} handlers over the already-present {@code vertx-http} server (the same extension
 * forvum-app's {@code CaprDashboardRoute} uses — chosen over {@code quarkus-rest} so it does not perturb
 * the langchain4j {@code HttpClientFactorySelector}/REST-client stack, CLAUDE.md X6).
 *
 * <ul>
 *   <li><strong>GET</strong> {@value #PATH} — Meta's verification handshake: echo {@code hub.challenge}
 *       iff {@code hub.mode == "subscribe"} and {@code hub.verify_token} matches the configured
 *       {@code verifyToken}; otherwise {@code 403}.</li>
 *   <li><strong>POST</strong> {@value #PATH} — a signed event notification: the
 *       {@code X-Hub-Signature-256} HMAC is validated against the app secret over the RAW body
 *       ({@link WhatsAppSignature}) BEFORE any processing; an invalid/absent signature is {@code 403} and
 *       no turn runs. A valid POST is ACKED IMMEDIATELY with {@code 200} and its text messages are driven
 *       on virtual-thread workers — Meta RETRIES an un-acked delivery (→ duplicate turns), so the turn
 *       must never block the response (the Slack ack-deadline lesson, applied up front).</li>
 * </ul>
 *
 * <p>Both handlers are {@code type = BLOCKING}: they read {@code channels/whatsapp.json} on demand (file
 * IO, hot-reload of {@code allowedUserIds} without a restart) and must run off the Vert.x IO thread
 * (CLAUDE.md §11 — blocking work never on the event loop). With no {@code ~/.forvum/} the config is
 * {@link Spec#empty()} (disabled, no {@code verifyToken}/{@code appSecret}), so verification and every POST
 * are rejected and the channel is inert — the no-config native smoke contract. The webhook must be
 * reachable from Meta's servers; exposing the port publicly (a tunnel / ingress) is the operator's
 * concern, a documented limitation.
 */
@ApplicationScoped
public class WhatsAppWebhook {

    private static final Logger LOG = Logger.getLogger(WhatsAppWebhook.class);

    /** The webhook callback path the operator registers in the Meta App dashboard. */
    static final String PATH = "/webhooks/whatsapp";

    private final ObjectMapper mapper = new ObjectMapper();
    /** Per-message turn workers: a valid POST is acked fast, the turns run here (CLAUDE.md §3.8 VT). */
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    @Inject
    WhatsAppChannelConfig config;

    @Inject
    MessageProcessor processor;

    @Inject
    @RestClient
    GraphApi api;

    void onStop(@Observes ShutdownEvent event) {
        workers.shutdownNow();
    }

    /** Meta's GET verification handshake. */
    @Route(path = PATH, methods = Route.HttpMethod.GET, type = Route.HandlerType.BLOCKING)
    void verify(RoutingContext rc) {
        Spec spec = config.read();
        String mode = rc.request().getParam("hub.mode");
        String token = rc.request().getParam("hub.verify_token");
        String challenge = rc.request().getParam("hub.challenge");
        if ("subscribe".equals(mode) && challenge != null
                && spec.verifyToken().isPresent() && spec.verifyToken().get().equals(token)) {
            rc.response().setStatusCode(200).putHeader("Content-Type", "text/plain").end(challenge);
        } else {
            // Never log the tokens — only that verification was rejected.
            LOG.warn("WhatsApp: webhook verification rejected (mode/verify_token mismatch or unconfigured).");
            rc.response().setStatusCode(403).end();
        }
    }

    /** A signed event notification POST: validate the signature, ack fast, drive turns on VTs. */
    @Route(path = PATH, methods = Route.HttpMethod.POST, type = Route.HandlerType.BLOCKING)
    void receive(RoutingContext rc) {
        Spec spec = config.read();
        String signature = rc.request().getHeader(WhatsAppSignature.HEADER);
        Buffer buffer = rc.body() == null ? null : rc.body().buffer();
        byte[] rawBody = buffer == null ? null : buffer.getBytes();
        if (spec.appSecret().isEmpty()
                || !WhatsAppSignature.isValid(rawBody, spec.appSecret().get(), signature)) {
            LOG.warn("WhatsApp: rejected a webhook POST with an invalid or absent X-Hub-Signature-256.");
            rc.response().setStatusCode(403).end();
            return;
        }
        // Signature valid → ACK IMMEDIATELY (Meta retries an un-acked delivery → duplicate turns), then
        // drive each text message's turn on a virtual thread so the response is never held by inference.
        rc.response().setStatusCode(200).end();

        List<InboundMessage> messages =
                WhatsAppEvents.parse(mapper, new String(rawBody, StandardCharsets.UTF_8));
        for (InboundMessage message : messages) {
            workers.submit(() -> {
                try {
                    processor.process(message, spec, api);
                } catch (RuntimeException e) {
                    // One bad message must not kill the worker; content is never logged.
                    LOG.warnf("WhatsApp: failed to process an inbound message (%s); continuing.",
                            MessageProcessor.redact(e.getMessage()));
                }
            });
        }
    }
}
