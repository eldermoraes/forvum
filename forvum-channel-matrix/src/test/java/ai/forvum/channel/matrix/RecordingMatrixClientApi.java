package ai.forvum.channel.matrix;

import ai.forvum.channel.matrix.dto.JoinRequest;
import ai.forvum.channel.matrix.dto.SendMessageRequest;
import ai.forvum.channel.matrix.dto.SyncResponse;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test double for {@link MatrixClientApi}: {@code sync} replays scripted responses (then returns an
 * empty batch carrying a fresh cursor) and records every {@code (since, authorization)} it was called
 * with; {@code sendMessage}/{@code join} record every outbound call. Used directly (not as a CDI bean)
 * so {@code SyncProcessor} and {@code MatrixChannel.syncLoop} can be driven without a live homeserver or
 * a mocked HTTP endpoint.
 */
class RecordingMatrixClientApi implements MatrixClientApi {

    final Deque<SyncResponse> scriptedResponses = new ArrayDeque<>();
    final CopyOnWriteArrayList<SyncCall> syncCalls = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<Sent> sent = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<Joined> joined = new CopyOnWriteArrayList<>();

    record SyncCall(String baseUrl, String authorization, String since) {
    }

    record Sent(String roomId, String txnId, SendMessageRequest body) {
    }

    record Joined(String roomId) {
    }

    @Override
    public SyncResponse sync(String baseUrl, String authorization, String since, int timeout) {
        syncCalls.add(new SyncCall(baseUrl, authorization, since));
        SyncResponse next = scriptedResponses.poll();
        return next != null ? next : new SyncResponse("empty-batch", null);
    }

    @Override
    public void sendMessage(String baseUrl, String authorization, String roomId, String txnId,
                            SendMessageRequest body) {
        sent.add(new Sent(roomId, txnId, body));
    }

    @Override
    public void join(String baseUrl, String authorization, String roomId, JoinRequest body) {
        joined.add(new Joined(roomId));
    }
}
