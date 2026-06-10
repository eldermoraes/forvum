package ai.forvum.channel.matrix;

import ai.forvum.channel.matrix.MatrixChannelConfig.Spec;
import ai.forvum.channel.matrix.MatrixSyncProtocol.InboundMessage;
import ai.forvum.channel.matrix.MatrixSyncProtocol.Invite;
import ai.forvum.channel.matrix.dto.SyncResponse;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Matrix channel's inbound surface (ULTRAPLAN §5.5, P2-CH): a {@code /sync} long-poll worker that
 * pulls each batch, hands its messages and invites to {@link SyncProcessor}, and advances the
 * {@code since} cursor to {@code next_batch}, repeating until shutdown — the Telegram long-poll recipe
 * over the Matrix client-server API v3. Per Risk #12 and CLAUDE.md §3.8 the worker runs on a virtual
 * thread ({@link Executors#newVirtualThreadPerTaskExecutor()}) so the blocking REST client never pins a
 * carrier thread — {@code @RunOnVirtualThread} applies only to externally-invoked callbacks, so a loop
 * this bean starts itself must be placed on a virtual thread via the executor.
 *
 * <p><strong>Initial snapshot discarded.</strong> The FIRST sync (no {@code since}) returns the
 * account's current snapshot, whose timelines are recent HISTORY, not new traffic — its message events
 * are discarded (only {@code next_batch} is taken) so the bot never replays old conversations on every
 * boot. Pending invites in the snapshot ARE processed: an invite received while the bot was offline
 * appears only here (a still-pending invite is not repeated by later incremental syncs), and a join is
 * idempotent, not a replay.
 *
 * <p><strong>Absent credentials or identity → warn + no-op.</strong> If {@code channels/matrix.json} is
 * absent, the channel is disabled, or {@code homeserver}/{@code accessToken}/{@code userId} is unset,
 * the loop is NOT started and the bean logs and returns — it never throws and never blocks. This keeps
 * the CI native no-config boot (no {@code ~/.forvum/}) graceful, the same contract the M4 watcher and
 * the other channels honor. {@code userId} is serve-required because Matrix {@code /sync} echoes the
 * bot's OWN sends (unlike Telegram's {@code getUpdates} or Discord's bot-author filter): without its
 * own id the bot cannot self-filter, and an enabled channel would loop on its own replies.
 *
 * <p><strong>Unencrypted rooms only (no E2EE).</strong> End-to-end encryption is NOT supported in v0.x:
 * there is no native-clean Olm/Megolm path on GraalVM (vodozemac is Rust; the Java bindings are
 * JNI/reflection-heavy), so encrypted rooms' {@code m.room.encrypted} events are simply never matched by
 * the protocol layer and the bot stays silent there. Use the bot in unencrypted rooms. Tracked in
 * issue #125.
 */
@ApplicationScoped
public class MatrixChannel {

    private static final Logger LOG = Logger.getLogger(MatrixChannel.class);

    @Inject
    @RestClient
    MatrixClientApi api;

    @Inject
    MatrixChannelConfig config;

    @Inject
    SyncProcessor processor;

    /**
     * Long-poll timeout in milliseconds passed to {@code /sync}. {@code quarkus.rest-client.
     * "matrix-client-api".read-timeout} MUST exceed this (see
     * {@code META-INF/microprofile-config.properties}). Defaults to 30 000 ms.
     */
    @ConfigProperty(name = "forvum.channel.matrix.sync-timeout-millis", defaultValue = "30000")
    int syncTimeoutMillis;

    /** Package-private so a sync-loop test can start/stop a single deterministic cycle. */
    volatile boolean running;
    private ExecutorService syncer;

    /** Retry schedule for a failed sync; package-private so a test can shrink the delays. */
    Backoff backoff = new Backoff();

    void onStart(@Observes StartupEvent event) {
        Spec spec = config.read();
        if (!spec.enabled()) {
            LOG.info("Matrix channel disabled (no channels/matrix.json, or \"enabled\": false); "
                    + "not starting the sync loop.");
            return;
        }
        if (spec.homeserver().isEmpty() || spec.accessToken().isEmpty()) {
            LOG.warn("Matrix channel enabled but homeserver/accessToken missing in channels/matrix.json; "
                    + "not starting the sync loop. Set both to activate the channel.");
            return;
        }
        if (spec.userId().isEmpty()) {
            LOG.warn("Matrix channel enabled but \"userId\" missing in channels/matrix.json; not "
                    + "starting the sync loop. Matrix /sync echoes the bot's OWN sends, so without its "
                    + "own user id the bot cannot self-filter and would reply to itself in an unbounded "
                    + "loop (one model call per cycle). Set userId to the bot's user id "
                    + "(e.g. @bot:example.org) to activate the channel.");
            return;
        }
        String baseUrl = stripTrailingSlashes(spec.homeserver().get());
        String authorization = "Bearer " + spec.accessToken().get();
        running = true;
        syncer = Executors.newVirtualThreadPerTaskExecutor();
        syncer.submit(() -> syncLoop(api, baseUrl, authorization));
        LOG.infof("Matrix channel started: long-polling /sync (timeout %d ms) against %s on a virtual "
                + "thread. Unencrypted rooms only — E2EE is not supported (issue #125).",
                syncTimeoutMillis, baseUrl);
    }

    /**
     * Whether the sync loop is live. A METHOD (not direct field access) so a {@code @QuarkusTest} can
     * assert the inert no-config boot through the {@code @ApplicationScoped} client proxy — field reads
     * on a proxy do not delegate to the contextual instance, only method calls do.
     */
    boolean isRunning() {
        return running;
    }

    void onStop(@Observes ShutdownEvent event) {
        running = false;
        if (syncer != null) {
            syncer.shutdownNow();
        }
    }

    /**
     * The sync loop: {@code GET /sync(since, timeout)} → process the batch's invites + messages →
     * advance {@code since} to {@code next_batch}, repeating until {@link #onStop}. The first iteration
     * (no {@code since}) is the initial snapshot: {@code next_batch} is taken, invites are handled, and
     * timeline messages are DISCARDED (see the class javadoc). A sync/parse failure is logged (redacted)
     * and the loop continues after an exponential back-off, reset on the next success, so a transient
     * homeserver error (incl. {@code M_UNKNOWN_TOKEN} responses) never kills the channel; a failure
     * processing ONE message/invite is caught per event, so a poison event never blocks the cursor
     * advance or re-dispatches its batch siblings. Re-reads the {@link Spec} each iteration so an
     * operator's {@code allowedUserIds} edit takes effect on the next cycle without a restart.
     *
     * <p>The {@link MatrixClientApi} is a parameter (not the injected field) so a test can drive cycles
     * with a recording client; production passes the injected {@code @RestClient} bean.
     */
    void syncLoop(MatrixClientApi api, String baseUrl, String authorization) {
        String since = null;
        while (running) {
            try {
                Spec spec = config.read();
                SyncResponse response = api.sync(baseUrl, authorization, since, syncTimeoutMillis);
                backoff.reset();
                String ownUserId = spec.userId().orElse(null);
                if (ownUserId == null) {
                    // onStart gates the loop on userId, so null here means a mid-run config edit
                    // removed it. Fail safe: the protocol layer yields no messages and no trusted
                    // inviter without the bot's own id, so this cycle processes nothing (its batch
                    // is dropped, like the initial snapshot) until the operator restores userId.
                    LOG.warn("Matrix: \"userId\" disappeared from channels/matrix.json; without the "
                            + "bot's own id no event can be proven not-self, so nothing is processed "
                            + "until it is restored.");
                }
                // Each message/invite is isolated in its own try/catch (the M4 observer lesson): one
                // poison event must never abort its batch siblings or block the cursor advance below —
                // an escaping throw would re-fetch (and re-dispatch) the same batch forever.
                for (Invite invite : MatrixSyncProtocol.invites(response, ownUserId)) {
                    try {
                        processor.processInvite(invite, spec, api, baseUrl, authorization);
                    } catch (RuntimeException e) {
                        LOG.warnf("Matrix: failed to process an invite to room %s (%s); skipping it.",
                                invite.roomId(), redact(e.getMessage()));
                    }
                }
                if (since != null) { // not the initial snapshot — its timeline is history, discarded
                    for (InboundMessage message : MatrixSyncProtocol.messages(response, ownUserId)) {
                        try {
                            processor.process(message, spec, api, baseUrl, authorization);
                        } catch (RuntimeException e) {
                            LOG.warnf("Matrix: failed to process a message in room %s (%s); "
                                    + "skipping it.", message.roomId(), redact(e.getMessage()));
                        }
                    }
                }
                String nextBatch = MatrixSyncProtocol.nextBatch(response);
                if (nextBatch != null && !nextBatch.isBlank()) {
                    since = nextBatch;
                }
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                // Do NOT log the raw throwable: the access token rides the Authorization header, so a
                // REST-client exception message/stack can leak the secret to logs. Log a redacted
                // message instead (no stack trace).
                LOG.warnf("Matrix: /sync failed (%s); retrying after back-off.", redact(e.getMessage()));
                backOff();
            }
        }
    }

    /**
     * Replace any {@code Bearer <token>} sequence (the Authorization header echo) and any
     * {@code access_token=...} query-param echo in a log-bound string with a redacted marker so the
     * Matrix access token never reaches the logs. Forvum itself never puts the token in a URL, but a
     * proxy/homeserver error text might echo either form — both are masked. Null-safe.
     */
    static String redact(String message) {
        if (message == null) {
            return null;
        }
        return message
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer <redacted>")
                .replaceAll("(?i)access_token=[^&\\s]+", "access_token=<redacted>");
    }

    /** The homeserver base URL without trailing slashes, so {@code @Url} + the v3 paths concatenate cleanly. */
    static String stripTrailingSlashes(String homeserver) {
        String base = homeserver;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /** Exponential back-off after a failed sync, interrupt-aware so shutdown is prompt. */
    private void backOff() {
        try {
            Thread.sleep(backoff.nextDelayMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
