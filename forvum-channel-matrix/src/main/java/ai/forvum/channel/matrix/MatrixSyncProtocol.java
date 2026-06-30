package ai.forvum.channel.matrix;

import ai.forvum.channel.matrix.MatrixChannelConfig.Spec;
import ai.forvum.channel.matrix.dto.RoomEvent;
import ai.forvum.channel.matrix.dto.SyncInvitedRoom;
import ai.forvum.channel.matrix.dto.SyncJoinedRoom;
import ai.forvum.channel.matrix.dto.SyncResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The pure Matrix sync protocol layer: maps one decoded {@code /sync} response to the inbound messages
 * and pending invites the channel acts on, plus the {@code next_batch} cursor — and decides
 * join-vs-ignore for an invite. Pure static functions over the {@code dto} records and two plain value
 * records; NO sockets, NO HTTP, NO CDI — so the whole protocol is unit-testable with recorded
 * {@code /sync} fixtures and no live homeserver (the Discord {@code GatewayProtocol} discipline).
 *
 * <p>Every walker is defensive: a {@code null} response, absent {@code rooms} section, absent timeline,
 * or malformed event (missing sender/content/body) yields an empty result or is skipped — a homeserver
 * quirk must never throw inside the sync loop.
 */
final class MatrixSyncProtocol {

    /** The {@code m.room.message} event type — the only timeline event kind that drives a turn. */
    static final String MESSAGE_EVENT_TYPE = "m.room.message";

    /** The {@code m.text} msgtype — the only message content kind that drives a turn. */
    static final String TEXT_MSGTYPE = "m.text";

    /** The {@code m.room.member} event type — the stripped state event carrying an invite. */
    static final String MEMBER_EVENT_TYPE = "m.room.member";

    /** The {@code invite} membership value marking a pending-invite member event. */
    static final String INVITE_MEMBERSHIP = "invite";

    private MatrixSyncProtocol() {
    }

    /** One inbound text message extracted from a joined room's timeline. Never JSON-serialized. */
    record InboundMessage(String roomId, String sender, String body) {
    }

    /** One pending room invite; {@code inviter} is {@code null} when undetectable. Never JSON-serialized. */
    record Invite(String roomId, String inviter) {
    }

    /** The {@code next_batch} cursor of {@code response}, or {@code null} when absent. Null-safe. */
    static String nextBatch(SyncResponse response) {
        return response == null ? null : response.nextBatch();
    }

    /**
     * The inbound text messages of {@code response}: every {@code rooms.join.<roomId>.timeline} event
     * with {@code type == m.room.message}, {@code content.msgtype == m.text}, a non-blank
     * {@code sender} (a blank one would violate {@code ChannelMessage}'s invariant inside the sync
     * loop), a non-null {@code body}, and a sender that is NOT {@code ownUserId} (the self-filter — the
     * bot's own replies come back in subsequent syncs and must never drive a turn, or the bot converses
     * with itself). A {@code null} {@code ownUserId} FAILS SAFE to no messages at all: without the
     * bot's own id no event can be proven not-self, and processing anyway would open an unbounded
     * self-echo loop ({@code MatrixChannel.onStart} gates the loop on {@code userId}, so null here
     * means a mid-run config edit removed it).
     */
    static List<InboundMessage> messages(SyncResponse response, String ownUserId) {
        List<InboundMessage> messages = new ArrayList<>();
        if (response == null || ownUserId == null
                || response.rooms() == null || response.rooms().join() == null) {
            return messages;
        }
        for (Map.Entry<String, SyncJoinedRoom> room : response.rooms().join().entrySet()) {
            SyncJoinedRoom joined = room.getValue();
            if (joined == null || joined.timeline() == null || joined.timeline().events() == null) {
                continue;
            }
            for (RoomEvent event : joined.timeline().events()) {
                if (event == null
                        || !MESSAGE_EVENT_TYPE.equals(event.type())
                        || event.sender() == null
                        || event.sender().isBlank()
                        || event.sender().equals(ownUserId)
                        || event.content() == null
                        || !TEXT_MSGTYPE.equals(event.content().msgtype())
                        || event.content().body() == null) {
                    continue;
                }
                messages.add(new InboundMessage(room.getKey(), event.sender(), event.content().body()));
            }
        }
        return messages;
    }

    /**
     * The pending invites of {@code response}: one {@link Invite} per {@code rooms.invite} entry, the
     * inviter taken from the SINGLE stripped {@code m.room.member} event with {@code content.membership
     * == invite} targeting {@code ownUserId}. An invite whose member event is missing, ambiguous
     * (stripped state is unauthenticated — see {@link #inviterOf}), or untargetable ({@code ownUserId}
     * null) yields {@code inviter == null} — which {@link #shouldJoin} ignores.
     */
    static List<Invite> invites(SyncResponse response, String ownUserId) {
        List<Invite> invites = new ArrayList<>();
        if (response == null || response.rooms() == null || response.rooms().invite() == null) {
            return invites;
        }
        for (Map.Entry<String, SyncInvitedRoom> room : response.rooms().invite().entrySet()) {
            invites.add(new Invite(room.getKey(), inviterOf(room.getValue(), ownUserId)));
        }
        return invites;
    }

    /**
     * Whether the channel should auto-join {@code invite}: only when the inviter is identifiable AND
     * passes {@code allowedUserIds} (an unidentifiable inviter is never trusted; since #170 an empty
     * allow-list denies every inviter unless {@code allowAllUsers} is set). The ignore log line is the
     * caller's concern.
     */
    static boolean shouldJoin(Invite invite, Spec spec) {
        return invite.inviter() != null && spec.isUserAllowed(invite.inviter());
    }

    /**
     * The sender of the pending-invite member event of {@code room}, or {@code null} when it cannot be
     * trusted. Stripped {@code invite_state} events are UNAUTHENTICATED per the Matrix spec — over
     * federation they come from the inviting server's {@code invite_room_state}, which a malicious
     * homeserver fully controls — so the invite gate is best-effort, defense-in-depth, never proof.
     * Concretely: current state is keyed by {@code (type, state_key)}, so a legitimate stripped set
     * carries exactly ONE {@code m.room.member}/{@code invite} event targeting the bot; ANY
     * multiplicity (the forged-allowlisted-event-plus-real-event spoof shape) yields {@code null} —
     * never trusted, so {@link #shouldJoin} refuses the auto-join. A {@code null} {@code ownUserId}
     * also yields {@code null}: without the bot's own id the member event targeting it cannot be
     * identified (fail safe, mirroring {@link #messages}).
     */
    private static String inviterOf(SyncInvitedRoom room, String ownUserId) {
        if (room == null || ownUserId == null
                || room.inviteState() == null || room.inviteState().events() == null) {
            return null;
        }
        String inviter = null;
        int matches = 0;
        for (RoomEvent event : room.inviteState().events()) {
            if (event == null
                    || !MEMBER_EVENT_TYPE.equals(event.type())
                    || event.content() == null
                    || !INVITE_MEMBERSHIP.equals(event.content().membership())
                    || !ownUserId.equals(event.stateKey())) {
                continue;
            }
            matches++;
            inviter = event.sender();
        }
        return matches == 1 ? inviter : null;
    }
}
