package ai.forvum.engine.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.engine.persistence.SessionEntity;
import ai.forvum.engine.security.OutputFilteredException;
import ai.forvum.sdk.ChannelSender;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ChannelSendCronDeliverySink} routing (#188): {@code explicit-to} reaches the installed
 * {@link ChannelSender} matching the target channel (default destination); {@code last} reaches the
 * sender of the most recently seen sender-capable session, targeting that session's native user; and
 * every no-sender/unconfigured/failing path falls back to the {@link LoggingCronDeliverySink} so the
 * pre-#188 cron contract is preserved. Pure logic — the latest-session lookup is stubbed through its
 * package-private seam, no Quarkus boot (mirrors {@link CronDeliveryRoutingTest}).
 */
class ChannelSendCronDeliverySinkTest {

    /** A recording {@link ChannelSender} double. */
    static final class RecordingSender implements ChannelSender {
        final String id;
        final List<String[]> sent = new ArrayList<>();
        boolean configured = true;
        boolean explode;

        RecordingSender(String id) {
            this.id = id;
        }

        @Override
        public String extensionId() {
            return id;
        }

        @Override
        public boolean send(String target, String text) {
            if (explode) {
                throw new RuntimeException("transport boom");
            }
            if (!configured) {
                return false;
            }
            sent.add(new String[] {target, text});
            return true;
        }
    }

    /** A recording {@link LoggingCronDeliverySink} double (the fallback seam). */
    static final class RecordingFallback extends LoggingCronDeliverySink {
        final List<CronDelivery> delivered = new ArrayList<>();

        @Override
        public void deliver(CronDelivery delivery) {
            delivered.add(delivery);
        }
    }

    private static CronDelivery explicitTo(String channelId) {
        return new CronDelivery("daily", "main", "the reply",
                new Delivery(DeliveryMode.EXPLICIT_TO, channelId));
    }

    private static CronDelivery last() {
        return new CronDelivery("daily", "main", "the reply", new Delivery(DeliveryMode.LAST, null));
    }

    private static ChannelSendCronDeliverySink sinkWith(RecordingFallback fallback,
                                                        SessionEntity latestSession,
                                                        ChannelSender... senders) {
        return new ChannelSendCronDeliverySink(List.of(senders), fallback) {
            @Override
            Optional<SessionEntity> latestSessionOn(Set<String> channelIds) {
                return Optional.ofNullable(latestSession)
                        .filter(s -> channelIds.contains(s.channelId));
            }

            @Override
            Set<String> allowedUserIdsOf(String channelId) {
                // The routing tests treat the latest session's native user as an allowlisted member;
                // the #188 membership rule itself is exercised by the dedicated deny test below.
                if (latestSession != null && latestSession.channelId.equals(channelId)) {
                    return Set.of(latestSession.id.substring(channelId.length() + 1));
                }
                return Set.of();
            }
        };
    }

    private static SessionEntity session(String channelId, String nativeUserId) {
        SessionEntity session = new SessionEntity();
        session.id = channelId + ":" + nativeUserId;
        session.channelId = channelId;
        return session;
    }

    @Test
    void explicitToDeliversThroughTheMatchingSenderWithTheChannelDefaultDestination() {
        RecordingSender telegram = new RecordingSender("telegram");
        RecordingFallback fallback = new RecordingFallback();

        sinkWith(fallback, null, telegram).deliver(explicitTo("telegram"));

        assertEquals(1, telegram.sent.size(), "explicit-to reaches the live channel");
        assertEquals("", telegram.sent.getFirst()[0], "the cron names a channel, not a chat — default destination");
        assertEquals("the reply", telegram.sent.getFirst()[1]);
        assertTrue(fallback.delivered.isEmpty(), "a delivered reply never also hits the log sink");
    }

    @Test
    void explicitToWithNoInstalledSenderFallsBackToTheLoggedSink() {
        RecordingFallback fallback = new RecordingFallback();
        sinkWith(fallback, null).deliver(explicitTo("telegram"));

        assertEquals(1, fallback.delivered.size(), "no sender installed — the pre-#188 logged sink runs");
    }

    @Test
    void explicitToWithAnUnconfiguredSenderFallsBackToTheLoggedSink() {
        RecordingSender telegram = new RecordingSender("telegram");
        telegram.configured = false;
        RecordingFallback fallback = new RecordingFallback();

        sinkWith(fallback, null, telegram).deliver(explicitTo("telegram"));

        assertTrue(telegram.sent.isEmpty());
        assertEquals(1, fallback.delivered.size(), "an unconfigured sender falls back, never drops");
    }

    @Test
    void aSenderFailureIsCaughtAndFallsBackToTheLoggedSink() {
        RecordingSender telegram = new RecordingSender("telegram");
        telegram.explode = true;
        RecordingFallback fallback = new RecordingFallback();

        ChannelSendCronDeliverySink sink = sinkWith(fallback, null, telegram);
        sink.deliver(explicitTo("telegram")); // must not throw — delivery is fire-and-forget

        assertEquals(1, fallback.delivered.size());
    }

    @Test
    void lastDeliversToTheMostRecentSessionsChannelAndNativeUser() {
        RecordingSender telegram = new RecordingSender("telegram");
        RecordingFallback fallback = new RecordingFallback();

        sinkWith(fallback, session("telegram", "42"), telegram).deliver(last());

        assertEquals(1, telegram.sent.size(), "last reaches the live channel of the latest session");
        assertEquals("42", telegram.sent.getFirst()[0], "targeted at the session's native user");
        assertTrue(fallback.delivered.isEmpty());
    }

    @Test
    void lastWithNoSenderCapableSessionFallsBackToTheLoggedSink() {
        RecordingSender telegram = new RecordingSender("telegram");
        RecordingFallback fallback = new RecordingFallback();

        // The only session is on a channel with no sender (e.g. the CLI) — the seam filters it out.
        sinkWith(fallback, session("cli", "local"), telegram).deliver(last());

        assertTrue(telegram.sent.isEmpty());
        assertEquals(1, fallback.delivered.size());
    }

    @Test
    void lastWithNoSendersInstalledFallsBackWithoutTouchingTheSessionLedger() {
        RecordingFallback fallback = new RecordingFallback();
        ChannelSendCronDeliverySink sink = new ChannelSendCronDeliverySink(List.of(), fallback) {
            @Override
            Optional<SessionEntity> latestSessionOn(Set<String> channelIds) {
                throw new AssertionError("no senders — the ledger must not be queried");
            }
        };

        sink.deliver(last());
        assertEquals(1, fallback.delivered.size());
    }

    @Test
    void lastDestinationNotInTheChannelAllowedUserIdsFallsBackToTheLoggedSink() {
        RecordingSender telegram = new RecordingSender("telegram");
        RecordingFallback fallback = new RecordingFallback();
        SessionEntity stale = session("telegram", "999");
        ChannelSendCronDeliverySink sink = new ChannelSendCronDeliverySink(List.of(telegram), fallback) {
            @Override
            Optional<SessionEntity> latestSessionOn(Set<String> channelIds) {
                return Optional.of(stale);
            }

            @Override
            Set<String> allowedUserIdsOf(String channelId) {
                return Set.of("42"); // the ledger's destination is NOT a configured member
            }
        };

        sink.deliver(last());

        assertTrue(telegram.sent.isEmpty(),
                "#188: a last-session destination outside allowedUserIds must never be sent to");
        assertEquals(1, fallback.delivered.size(), "membership denial falls back to the logged sink");
    }

    @Test
    void lastWithAnUnconfiguredChannelMembershipIsFailClosed() {
        RecordingSender telegram = new RecordingSender("telegram");
        RecordingFallback fallback = new RecordingFallback();
        SessionEntity latest = session("telegram", "42");
        // No allowedUserIdsOf override on top of the default null-reader seam: EMPTY set → nothing is
        // a member → the send is refused even though the ledger names a plausible destination.
        ChannelSendCronDeliverySink sink = new ChannelSendCronDeliverySink(List.of(telegram), fallback) {
            @Override
            Optional<SessionEntity> latestSessionOn(Set<String> channelIds) {
                return Optional.of(latest);
            }
        };

        sink.deliver(last());

        assertTrue(telegram.sent.isEmpty(), "no configured allowlist means no member — fail-closed");
        assertEquals(1, fallback.delivered.size());
    }

    @Test
    void aGuardBlockedReplyIsSuppressedEntirelyNeitherSentNorLoggedRaw() {
        RecordingSender telegram = new RecordingSender("telegram");
        RecordingFallback fallback = new RecordingFallback();
        ChannelSendCronDeliverySink sink = new ChannelSendCronDeliverySink(List.of(telegram), fallback) {
            @Override
            String guardEgress(CronDelivery delivery) {
                throw new OutputFilteredException("policy: secrets", null);
            }
        };

        sink.deliver(explicitTo("telegram"));

        assertTrue(telegram.sent.isEmpty(), "a Blocked disposition must never reach a sender");
        assertTrue(fallback.delivered.isEmpty(),
                "#188: a guard-blocked payload is suppressed by design — no raw-payload fallback");
    }

    @Test
    void theSenderReceivesTheGuardedTextNotTheRawReply() {
        RecordingSender telegram = new RecordingSender("telegram");
        RecordingFallback fallback = new RecordingFallback();
        ChannelSendCronDeliverySink sink = new ChannelSendCronDeliverySink(List.of(telegram), fallback) {
            @Override
            String guardEgress(CronDelivery delivery) {
                return "[redacted] " + delivery.reply();
            }
        };

        sink.deliver(explicitTo("telegram"));

        assertEquals("[redacted] the reply", telegram.sent.getFirst()[1],
                "egress goes through the output-guard chain BEFORE the sender sees it");
    }
}
