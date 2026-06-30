package ai.forvum.channel.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.matrix.MatrixChannelConfig.Spec;
import ai.forvum.channel.matrix.MatrixSyncProtocol.InboundMessage;
import ai.forvum.channel.matrix.MatrixSyncProtocol.Invite;
import ai.forvum.channel.matrix.dto.SyncResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The pure protocol layer over recorded {@code /sync} fixtures: message extraction (with the own-echo
 * self-filter and the {@code m.text}-only rule), {@code next_batch} extraction, invite extraction (the
 * inviter from the stripped {@code m.room.member} event), the join/ignore decision, and malformed-input
 * tolerance. Deserializing the fixtures through Jackson also pins the DTOs' wire mapping
 * ({@code next_batch}, {@code invite_state}, {@code state_key}). Plain POJO tests — no Quarkus boot, no
 * live homeserver (the Discord {@code GatewayProtocol} discipline).
 */
class MatrixSyncProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BOT = "@bot:example.org";

    private static SyncResponse fixture(String json) {
        try {
            return MAPPER.readValue(json, SyncResponse.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(json, e);
        }
    }

    /** A recorded incremental /sync carrying one m.text message from @alice. */
    private static final String MESSAGE_FIXTURE = """
            { "next_batch": "s101",
              "rooms": { "join": { "!room:example.org": { "timeline": { "events": [
                { "type": "m.room.message", "sender": "@alice:example.org",
                  "event_id": "$1", "origin_server_ts": 1700000000000,
                  "content": { "msgtype": "m.text", "body": "hello bot" } }
              ], "limited": false, "prev_batch": "s100" } } } },
              "presence": { "events": [] }, "account_data": { "events": [] } }""";

    @Test
    void extractsATextMessageWithRoomSenderAndBody() {
        List<InboundMessage> messages = MatrixSyncProtocol.messages(fixture(MESSAGE_FIXTURE), BOT);

        assertEquals(List.of(new InboundMessage("!room:example.org", "@alice:example.org", "hello bot")),
                messages);
    }

    @Test
    void extractsNextBatch() {
        assertEquals("s101", MatrixSyncProtocol.nextBatch(fixture(MESSAGE_FIXTURE)));
        assertNull(MatrixSyncProtocol.nextBatch(null), "null response yields no cursor");
    }

    @Test
    void filtersTheBotsOwnEchoOut() {
        SyncResponse response = fixture("""
                { "next_batch": "s102",
                  "rooms": { "join": { "!room:example.org": { "timeline": { "events": [
                    { "type": "m.room.message", "sender": "@bot:example.org",
                      "content": { "msgtype": "m.text", "body": "echo:hello" } },
                    { "type": "m.room.message", "sender": "@alice:example.org",
                      "content": { "msgtype": "m.text", "body": "real" } }
                  ] } } } } }""");

        List<InboundMessage> messages = MatrixSyncProtocol.messages(response, BOT);

        assertEquals(1, messages.size(), "the bot's own message must never drive a turn");
        assertEquals("real", messages.get(0).body());
    }

    @Test
    void aNullOwnUserIdYieldsNoMessagesFailSafe() {
        // Without the bot's own id NO event can be proven not-self, and Matrix /sync echoes the bot's
        // own sends — so a null ownUserId (a mid-run config edit removed userId; boot is gated) must
        // process NOTHING rather than disable the self-filter and open an unbounded self-echo loop.
        assertTrue(MatrixSyncProtocol.messages(fixture(MESSAGE_FIXTURE), null).isEmpty(),
                "a null ownUserId must fail safe: no message may drive a turn");
    }

    @Test
    void aBlankSenderIsSkipped() {
        // A whitespace-only sender would pass a null-only check and then blow up ChannelMessage's
        // non-blank nativeUserId invariant INSIDE the sync loop — skip it at the protocol layer.
        SyncResponse response = fixture("""
                { "next_batch": "s104",
                  "rooms": { "join": { "!room:example.org": { "timeline": { "events": [
                    { "type": "m.room.message", "sender": "   ",
                      "content": { "msgtype": "m.text", "body": "poison" } }
                  ] } } } } }""");

        assertTrue(MatrixSyncProtocol.messages(response, BOT).isEmpty(),
                "a blank sender must be skipped like a missing one");
    }

    @Test
    void skipsNonTextMsgtypesAndNonMessageEvents() {
        SyncResponse response = fixture("""
                { "next_batch": "s103",
                  "rooms": { "join": { "!room:example.org": { "timeline": { "events": [
                    { "type": "m.room.message", "sender": "@alice:example.org",
                      "content": { "msgtype": "m.image", "body": "cat.png", "url": "mxc://x/y" } },
                    { "type": "m.room.member", "sender": "@alice:example.org",
                      "state_key": "@alice:example.org", "content": { "membership": "join" } },
                    { "type": "m.room.encrypted", "sender": "@alice:example.org",
                      "content": { "algorithm": "m.megolm.v1.aes-sha2" } }
                  ] } } } } }""");

        assertTrue(MatrixSyncProtocol.messages(response, BOT).isEmpty(),
                "only m.room.message with msgtype m.text drives a turn (E2EE events stay silent)");
    }

    @Test
    void toleratesMalformedAndAbsentSections() {
        assertTrue(MatrixSyncProtocol.messages(null, BOT).isEmpty(), "null response");
        assertTrue(MatrixSyncProtocol.invites(null, BOT).isEmpty(), "null response");
        assertTrue(MatrixSyncProtocol.messages(fixture("{ \"next_batch\": \"s1\" }"), BOT).isEmpty(),
                "absent rooms section");
        assertTrue(MatrixSyncProtocol.messages(fixture("""
                { "rooms": { "join": { "!r:x": {} } } }"""), BOT).isEmpty(), "absent timeline");
        assertTrue(MatrixSyncProtocol.messages(fixture("""
                { "rooms": { "join": { "!r:x": { "timeline": { "events": [
                  { "type": "m.room.message", "sender": "@a:x", "content": { "msgtype": "m.text" } },
                  { "type": "m.room.message", "content": { "msgtype": "m.text", "body": "no sender" } },
                  { "type": "m.room.message", "sender": "@a:x" }
                ] } } } } }"""), BOT).isEmpty(), "missing body/sender/content events are skipped");
    }

    /** A recorded /sync carrying one pending invite from @alice targeting the bot. */
    private static final String INVITE_FIXTURE = """
            { "next_batch": "s200",
              "rooms": { "invite": { "!invited:example.org": { "invite_state": { "events": [
                { "type": "m.room.name", "sender": "@alice:example.org", "state_key": "",
                  "content": { "name": "Team" } },
                { "type": "m.room.member", "sender": "@alice:example.org",
                  "state_key": "@bot:example.org", "content": { "membership": "invite" } }
              ] } } } } }""";

    @Test
    void extractsTheInviteWithItsInviter() {
        List<Invite> invites = MatrixSyncProtocol.invites(fixture(INVITE_FIXTURE), BOT);

        assertEquals(List.of(new Invite("!invited:example.org", "@alice:example.org")), invites);
    }

    @Test
    void anInviteWithoutAMemberEventHasNoInviter() {
        SyncResponse response = fixture("""
                { "rooms": { "invite": { "!invited:example.org": { "invite_state": { "events": [
                  { "type": "m.room.name", "sender": "@alice:example.org", "state_key": "",
                    "content": { "name": "Team" } }
                ] } } } } }""");

        List<Invite> invites = MatrixSyncProtocol.invites(response, BOT);

        assertEquals(1, invites.size());
        assertNull(invites.get(0).inviter());
    }

    @Test
    void aForgedMemberEventAlongsideTheRealOneYieldsNoInviter() {
        // Stripped invite_state events are UNAUTHENTICATED (composed from the inviting server's
        // invite_room_state, which a malicious federated homeserver fully controls). The concrete
        // spoof shape: a forged member event naming an allowlisted inviter is injected FIRST, the
        // real invite event follows. A legitimate invite carries exactly ONE member event for the
        // bot's state_key, so ANY multiplicity is never trusted — inviter must be null.
        SyncResponse response = fixture("""
                { "rooms": { "invite": { "!lure:example.org": { "invite_state": { "events": [
                  { "type": "m.room.member", "sender": "@alice:example.org",
                    "state_key": "@bot:example.org", "content": { "membership": "invite" } },
                  { "type": "m.room.member", "sender": "@mallory:evil.org",
                    "state_key": "@bot:example.org", "content": { "membership": "invite" } }
                ] } } } } }""");

        assertNull(MatrixSyncProtocol.invites(response, BOT).get(0).inviter(),
                "conflicting invite member events (the forged+real shape) must never name an inviter");
    }

    @Test
    void duplicateInviteMemberEventsAreNeverTrustedEvenWithOneSender() {
        // Current state is keyed by (type, state_key): a legitimate stripped set holds exactly one
        // member event for the bot. Two matches — even agreeing ones — are an anomaly, never trusted.
        SyncResponse response = fixture("""
                { "rooms": { "invite": { "!odd:example.org": { "invite_state": { "events": [
                  { "type": "m.room.member", "sender": "@alice:example.org",
                    "state_key": "@bot:example.org", "content": { "membership": "invite" } },
                  { "type": "m.room.member", "sender": "@alice:example.org",
                    "state_key": "@bot:example.org", "content": { "membership": "invite" } }
                ] } } } } }""");

        assertNull(MatrixSyncProtocol.invites(response, BOT).get(0).inviter());
    }

    @Test
    void aNullOwnUserIdYieldsNoInviterFailSafe() {
        // Without the bot's own id the member event targeting it cannot be identified — never trust
        // any inviter (shouldJoin then refuses the auto-join), mirroring the message fail-safe.
        assertNull(MatrixSyncProtocol.invites(fixture(INVITE_FIXTURE), null).get(0).inviter(),
                "a null ownUserId must fail safe: no inviter is ever trusted");
    }

    @Test
    void aMemberEventTargetingAnotherUserIsNotTheInviteSource() {
        SyncResponse response = fixture("""
                { "rooms": { "invite": { "!invited:example.org": { "invite_state": { "events": [
                  { "type": "m.room.member", "sender": "@mallory:example.org",
                    "state_key": "@someoneelse:example.org", "content": { "membership": "invite" } }
                ] } } } } }""");

        assertNull(MatrixSyncProtocol.invites(response, BOT).get(0).inviter(),
                "an invite member event for a different user must not name the inviter");
    }

    @Test
    void joinsOnlyWhenTheInviterPassesAllowedUserIds() {
        Spec restricted = new Spec(true, Optional.of("https://m.org"), Optional.of("t"),
                Optional.of(BOT), Set.of("@alice:example.org"), false);
        Spec allowAny = new Spec(true, Optional.of("https://m.org"), Optional.of("t"),
                Optional.of(BOT), Set.of(), true);

        assertTrue(MatrixSyncProtocol.shouldJoin(new Invite("!r:x", "@alice:example.org"), restricted));
        assertFalse(MatrixSyncProtocol.shouldJoin(new Invite("!r:x", "@mallory:example.org"), restricted),
                "an invite from a disallowed user is ignored");
        assertTrue(MatrixSyncProtocol.shouldJoin(new Invite("!r:x", "@anyone:example.org"), allowAny),
                "public mode permits any identifiable inviter");
        assertFalse(MatrixSyncProtocol.shouldJoin(new Invite("!r:x", null), allowAny),
                "an unidentifiable inviter is never trusted, even in public mode");
    }
}
