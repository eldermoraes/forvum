package ai.forvum.engine.cron;

import ai.forvum.core.id.AgentId;
import ai.forvum.engine.config.ChannelReader;
import ai.forvum.engine.persistence.SessionEntity;
import ai.forvum.engine.security.OutputFilteredException;
import ai.forvum.engine.security.OutputGuardChain;
import ai.forvum.sdk.ChannelSender;
import ai.forvum.sdk.HookLayer;
import ai.forvum.sdk.OutputContext;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * <p>Hardening (#188 audit): the {@code last} route is delivered ONLY when the resolved destination is
 * a member of the channel's configured {@code allowedUserIds} (read from {@code channels/<id>.json}) —
 * an unlisted or unconfigured recipient falls back to the log sink, so a stale/poisoned session row can
 * never aim a cron at an arbitrary chat. And the cron's reply runs through the {@link OutputGuardChain}
 * ({@code PRE_CHANNEL_EMIT}) BEFORE any sender sees it: a Blocked disposition suppresses the delivery
 * entirely (reason logged, payload NEVER logged, and no raw-payload fallback — suppressed by design).
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
    private final ChannelReader channels;
    private final OutputGuardChain guards;

    @Inject
    public ChannelSendCronDeliverySink(Instance<ChannelSender> senders, LoggingCronDeliverySink fallback,
            ChannelReader channels, OutputGuardChain guards) {
        this.senders = senders;
        this.fallback = fallback;
        this.channels = channels;
        this.guards = guards;
    }

    /**
     * Package-private constructor wiring explicit collaborators — for tests, which override the
     * {@link #allowedUserIdsOf} and {@link #guardEgress} seams instead of wiring readers/guards.
     */
    ChannelSendCronDeliverySink(Iterable<ChannelSender> senders, LoggingCronDeliverySink fallback) {
        this.senders = senders;
        this.fallback = fallback;
        this.channels = null;
        this.guards = null;
    }

    @Override
    @ActivateRequestContext
    public void deliver(CronDelivery delivery) {
        // #188 audit: guard the egress BEFORE any routing — a Blocked disposition suppresses the
        // delivery entirely (no sender, and no raw-payload fallback to the log sink either: the guard
        // decided this content must not leave; only the REASON is logged, never the payload).
        String egress;
        try {
            egress = guardEgress(delivery);
        } catch (OutputFilteredException filtered) {
            LOG.warnf("Cron '%s' (agent '%s'): outbound delivery suppressed by an output guard (%s).",
                    delivery.cronId(), delivery.agentId(), filtered.getMessage());
            return;
        }
        Map<String, ChannelSender> byChannel = sendersByChannel();
        boolean delivered = switch (delivery.delivery().mode()) {
            case EXPLICIT_TO -> deliverExplicit(delivery, byChannel, egress);
            case LAST -> deliverLast(delivery, byChannel, egress);
            case NONE -> false; // the scheduler never routes NONE here; fall through to the log
        };
        if (!delivered) {
            fallback.deliver(delivery);
        }
    }

    private boolean deliverExplicit(CronDelivery delivery, Map<String, ChannelSender> byChannel,
            String egress) {
        String channelId = delivery.delivery().target();
        ChannelSender sender = byChannel.get(channelId);
        if (sender == null) {
            LOG.infof("Cron '%s': no installed outbound sender for channel '%s'; falling back to the "
                    + "logged sink.", delivery.cronId(), channelId);
            return false;
        }
        return trySend(delivery, sender, "", channelId, egress);
    }

    private boolean deliverLast(CronDelivery delivery, Map<String, ChannelSender> byChannel,
            String egress) {
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
        // #188 audit: the resolved "last" destination must be a configured channel member — the
        // sessions ledger is history, not authorization. Unlisted (or an unconfigured/empty
        // allowedUserIds) falls back to the log sink: fail-closed, never a raw send to a stray chat.
        if (!allowedUserIdsOf(channelId).contains(target)) {
            LOG.infof("Cron '%s': the last-session destination on channel '%s' is not among the "
                    + "channel's allowedUserIds; falling back to the logged sink.",
                    delivery.cronId(), channelId);
            return false;
        }
        return trySend(delivery, byChannel.get(channelId), target, channelId, egress);
    }

    private boolean trySend(CronDelivery delivery, ChannelSender sender, String target, String channelId,
            String egress) {
        try {
            boolean sent = sender.send(target, egress);
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
     * Run the cron's reply through the output-guard chain at the pre-channel-emit seam (#188 audit).
     * Package-private seam so unit tests can substitute the guard disposition without booting CDI.
     */
    String guardEgress(CronDelivery delivery) {
        if (guards == null) {
            return delivery.reply(); // explicit-collaborator test wiring: tests override this seam
        }
        return guards.enforce(
                new OutputContext(HookLayer.PRE_CHANNEL_EMIT, new AgentId(delivery.agentId()), null),
                delivery.reply());
    }

    /**
     * The channel's configured {@code allowedUserIds} from {@code channels/<id>.json} — the #188 cron
     * membership oracle. Absent file/field or empty array yields an EMPTY set (fail-closed: nothing is a
     * member). Package-private seam so the membership rule is unit-testable without a config home.
     */
    Set<String> allowedUserIdsOf(String channelId) {
        if (channels == null) {
            return Set.of(); // explicit-collaborator test wiring: fail-closed unless a test overrides
        }
        Optional<JsonNode> spec = channels.read(channelId);
        if (spec.isEmpty()) {
            return Set.of();
        }
        JsonNode ids = spec.get().path("allowedUserIds");
        if (!ids.isArray()) {
            return Set.of();
        }
        Set<String> members = new LinkedHashSet<>();
        for (JsonNode id : ids) {
            members.add(id.asText());
        }
        return Set.copyOf(members);
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
