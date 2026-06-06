package ai.forvum.channel.telegram;

import ai.forvum.channel.telegram.TelegramChannelConfig.Spec;
import ai.forvum.channel.telegram.dto.GetUpdatesResponse;
import ai.forvum.channel.telegram.dto.TelegramUpdate;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Telegram channel's inbound surface (ULTRAPLAN §5.5, M17): a long-poll worker that calls
 * {@code getUpdates}, hands each update to {@link UpdateProcessor}, and advances the offset, repeating
 * until shutdown. Per Risk #12 and CLAUDE.md §3.8 the worker runs on a virtual thread
 * ({@link Executors#newVirtualThreadPerTaskExecutor()}) so the blocking REST client never pins a carrier
 * thread — {@code @RunOnVirtualThread} applies only to externally-invoked callbacks, so a loop this bean
 * starts itself must be placed on a virtual thread via the executor.
 *
 * <p><strong>Absent token → warn + no-op.</strong> If {@code channels/telegram.json} is absent, the
 * channel is disabled, or no {@code botToken} is set, the loop is NOT started and the bean logs a
 * warning and returns — it never throws and never blocks. This keeps the CI native no-config boot (no
 * {@code ~/.forvum/}) graceful, the same contract the M4 watcher and the web channel honor.
 */
@ApplicationScoped
public class TelegramChannel {

    private static final Logger LOG = Logger.getLogger(TelegramChannel.class);

    /** Telegram Bot API base; the token is appended as {@code .../bot<TOKEN>} (ULTRAPLAN §5.5). */
    static final String API_ROOT = "https://api.telegram.org/bot";

    @Inject
    @RestClient
    TelegramBotApi api;

    @Inject
    TelegramChannelConfig config;

    @Inject
    UpdateProcessor processor;

    /**
     * Long-poll timeout in seconds passed to {@code getUpdates}. {@code quarkus.rest-client."telegram-bot-
     * api".read-timeout} MUST exceed this (see {@code application.properties}). Defaults to 50 s.
     */
    @ConfigProperty(name = "forvum.channel.telegram.poll-timeout-seconds", defaultValue = "50")
    int pollTimeoutSeconds;

    /** Package-private so a poll-loop test can start/stop a single deterministic cycle. */
    volatile boolean running;
    private ExecutorService poller;

    void onStart(@Observes StartupEvent event) {
        Spec spec = config.read();
        if (!spec.enabled()) {
            LOG.info("Telegram channel disabled (no channels/telegram.json, or \"enabled\": false); "
                    + "not starting the long-poll loop.");
            return;
        }
        if (spec.botToken().isEmpty()) {
            LOG.warn("Telegram channel enabled but no botToken in channels/telegram.json; not starting "
                    + "the long-poll loop. Set \"botToken\" to activate the channel.");
            return;
        }
        String baseUrl = API_ROOT + spec.botToken().get();
        running = true;
        poller = Executors.newVirtualThreadPerTaskExecutor();
        poller.submit(() -> pollLoop(api, baseUrl));
        LOG.infof("Telegram channel started: long-polling getUpdates (timeout %ds) on a virtual thread.",
                pollTimeoutSeconds);
    }

    void onStop(@Observes ShutdownEvent event) {
        running = false;
        if (poller != null) {
            poller.shutdownNow();
        }
    }

    /**
     * The long-poll loop: {@code getUpdates(offset, timeout)} → process each update → advance the offset
     * to {@code maxUpdateId + 1}, repeating until {@link #onStop}. A poll/parse failure is logged and the
     * loop continues after a short back-off so a transient Telegram error does not kill the channel
     * (Risk #8). Re-reads the {@link Spec} each iteration so an operator's {@code allowedUserIds} edit
     * takes effect on the next cycle without a restart.
     *
     * <p>The {@link TelegramBotApi} is a parameter (not the injected field) so a test can drive one cycle
     * with a recording client; production passes the injected {@code @RestClient} bean.
     */
    void pollLoop(TelegramBotApi api, String baseUrl) {
        long offset = 0;
        while (running) {
            try {
                Spec spec = config.read();
                GetUpdatesResponse response = api.getUpdates(baseUrl, offset, pollTimeoutSeconds);
                List<TelegramUpdate> updates = response == null ? null : response.result();
                if (updates != null) {
                    for (TelegramUpdate update : updates) {
                        processor.process(update, spec, api, baseUrl);
                        offset = Math.max(offset, update.updateId() + 1);
                    }
                }
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                // Do NOT log the raw throwable: the bot token is embedded in the request URL path
                // (.../bot<TOKEN>/getUpdates), so a REST-client exception message/stack can leak the
                // secret to logs. Log a redacted message instead (no stack trace).
                LOG.warnf("Telegram: getUpdates poll failed (%s); retrying after back-off.",
                        redact(e.getMessage()));
                backOff();
            }
        }
    }

    /**
     * Replace any {@code /bot<TOKEN>} segment in a log-bound string with {@code /bot<redacted>} so the
     * Telegram bot token (embedded in the request URL path) never reaches the logs. Null-safe.
     */
    static String redact(String message) {
        if (message == null) {
            return null;
        }
        return message.replaceAll("/bot[^/\\s]+", "/bot<redacted>");
    }

    /** Short back-off after a failed poll, interrupt-aware so shutdown is prompt. */
    private static void backOff() {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
