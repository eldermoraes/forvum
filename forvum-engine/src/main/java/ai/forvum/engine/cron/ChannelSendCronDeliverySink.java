package ai.forvum.engine.cron;

import ai.forvum.engine.persistence.SessionEntity;
import ai.forvum.sdk.ChannelSender;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The channel-backed {@link CronDeliverySink} (#188): it routes a cron's {@code last}/{@code
 * explicit-to} output into a live channel through the SDK {@link ChannelSender} SPI, realizing exactly
 * the "future outbound channel-send surface" the P2-CRON-DELIVERY sink contract reserved — the cron
 * contract is unchanged, and when no sender is installed/configured for the resolved channel the
 * delivery falls back to the {@link LoggingCronDeliverySink} (the pre-#188 behavior), never dropping
 * the output silently.
 *
 * <p>Routing: {@code explicit-to} targets the named channel id with the channel's default destination
 * (the operator names a channel in {@code crons/<id>.json}, not a chat); {@code last} resolves the most
 * recently seen session whose channel has an installed sender (the {@code sessions} ledger's
 * {@code last_seen_at}) and replies to that session's native user — "wherever the operator last talked".
 * Cron/CLI sessions never match (their channel ids have no sender).
 *
 * <p>Senders are discovered via CDI ({@code Instance<ChannelSender>}) from the app classpath — the
 * engine stays extension-agnostic, mirroring {@code ToolRegistry}'s {@code Instance<ToolProvider>}
 * discovery. Runs on the cron's virtual thread; a sender failure is caught and falls back to the log
 * (the caller treats delivery as fire-and-forget).
 */
@ApplicationScoped
public class ChannelSendCronDeliverySink implements CronDeliverySink {

    private static final Logger LOG = Logger.getLogger(ChannelSendCronDeliverySink.class);

    private final Iterable<ChannelSender> senders;
    private final LoggingCronDeliverySink fallback;

    @Inject
    public ChannelSendCronDeliverySink(Instance<ChannelSender> senders, LoggingCronDeliverySink fallback) {
        this.senders = senders;
        this.fallback = fallback;
    }

    /** Package-private constructor wiring explicit collaborators — for tests. */
    ChannelSendCronDeliverySink(Iterable<ChannelSender> senders, LoggingCronDeliverySink fallback) {
        this.senders = senders;
        this.fallback = fallback;
    }

    @Override
    @ActivateRequestContext
    public void deliver(CronDelivery delivery) {
        Map<String, ChannelSender> byChannel = sendersByChannel();
        boolean delivered = switch (delivery.delivery().mode()) {
            case EXPLICIT_TO -> deliverExplicit(delivery, byChannel);
            case LAST -> deliverLast(delivery, byChannel);
            case NONE -> false; // the scheduler never routes NONE here; fall through to the log
        };
        if (!delivered) {
            fallback.deliver(delivery);
        }
    }

    private boolean deliverExplicit(CronDelivery delivery, Map<String, ChannelSender> byChannel) {
        String channelId = delivery.delivery().target();
        ChannelSender sender = byChannel.get(channelId);
        if (sender == null) {
            LOG.infof("Cron '%s': no installed outbound sender for channel '%s'; falling back to the "
                    + "logged sink.", delivery.cronId(), channelId);
            return false;
        }
        return trySend(delivery, sender, "", channelId);
    }

    private boolean deliverLast(CronDelivery delivery, Map<String, ChannelSender> byChannel) {
        if (byChannel.isEmpty()) {
            return false;
        }
        Optional<SessionEntity> session = latestSessionOn(byChannel.keySet());
        if (session.isEmpty()) {
            LOG.infof("Cron '%s': no prior session on a sender-capable channel; falling back to the "
                    + "logged sink.", delivery.cronId());
            return false;
        }
        String channelId = session.get().channelId;
        // Session ids are composed as channelId + ":" + nativeUserId (TurnService); the suffix is the
        // channel-specific destination the last conversation came from.
        String target = session.get().id.startsWith(channelId + ":")
                ? session.get().id.substring(channelId.length() + 1)
                : "";
        return trySend(delivery, byChannel.get(channelId), target, channelId);
    }

    private boolean trySend(CronDelivery delivery, ChannelSender sender, String target, String channelId) {
        try {
            boolean sent = sender.send(target, delivery.reply());
            if (sent) {
                LOG.infof("Cron '%s' (agent '%s') delivered to channel '%s' (%s).",
                        delivery.cronId(), delivery.agentId(), channelId,
                        delivery.delivery().mode().wire());
            }
            return sent;
        } catch (RuntimeException e) {
            // Sender implementations sanitize their own messages (no secrets); keep it message-only.
            LOG.warnf("Cron '%s': outbound send on channel '%s' failed (%s); falling back to the "
                    + "logged sink.", delivery.cronId(), channelId, e.getMessage());
            return false;
        }
    }

    /** The installed senders keyed by channel/extension id (a duplicate id keeps the first). */
    private Map<String, ChannelSender> sendersByChannel() {
        Map<String, ChannelSender> byChannel = new LinkedHashMap<>();
        for (ChannelSender sender : senders) {
            byChannel.putIfAbsent(sender.extensionId(), sender);
        }
        return byChannel;
    }

    /**
     * The most recently seen session on any of {@code channelIds}, from the {@code sessions} ledger.
     * Package-private seam so the routing logic is unit-testable without booting Panache/SQLite.
     */
    Optional<SessionEntity> latestSessionOn(Set<String> channelIds) {
        return SessionEntity
                .<SessionEntity>find("channelId in ?1 order by lastSeenAt desc", channelIds)
                .firstResultOptional();
    }
}
