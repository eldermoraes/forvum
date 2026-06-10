package ai.forvum.channel.signal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Pure (socket-free) signal-cli event protocol logic: classify one complete SSE event from the daemon's
 * {@code /api/v1/events} stream into the sealed {@link Inbound} — either a {@link TextMessage} the
 * channel turns into a turn, or an {@link Ignored} with the reason. Keeping this layer free of any HTTP
 * dependency is what makes the receive flow unit-testable with recorded daemon fixtures and no live
 * signal-cli (the P2-CH testability requirement). Mirrors the Discord channel's {@code GatewayProtocol}.
 *
 * <p>The daemon emits each Signal envelope as an SSE event named {@code receive} whose {@code data} is a
 * JSON document. Two data shapes exist across signal-cli versions, both accepted here (each pinned by a
 * fixture test):
 *
 * <ul>
 *   <li>the bare receive payload — {@code { "envelope": { ... }, "account": "+1..." }} (the shape
 *       OpenClaw's signal extension consumes from the HTTP daemon);</li>
 *   <li>the JSON-RPC notification wrapping — {@code { "jsonrpc": "2.0", "method": "receive",
 *       "params": { "envelope": { ... }, "account": "+1..." } }} (signal-cli's JSON-RPC emit shape).</li>
 * </ul>
 *
 * <p>Only an envelope carrying {@code dataMessage} text from a direct conversation is consumed. Receipts
 * ({@code receiptMessage}), typing notifications ({@code typingMessage}), sync messages
 * ({@code syncMessage} — checked by property EXISTENCE, since signal-cli may set it to {@code null}
 * rather than omit it, and a replayed sent-transcript would otherwise loop the bot's own replies), and
 * group messages ({@code dataMessage.groupInfo} present; direct messages only in v0.5) are all
 * {@link Ignored}. The decision tree only READS the supplied {@link JsonNode} tree — no IO, no lock —
 * so it is safe to call from the consuming virtual thread.
 *
 * <p>Inbound parsing tree-walks {@code JsonNode} (like the config readers), so the inbound records carry
 * no reflection hint; the OUTBOUND JSON-RPC frames are Jackson-serialized records under {@code dto/} and
 * carry {@code @RegisterForReflection} (the native-frame rule).
 */
public final class SignalEvents {

    /** The SSE event name the daemon stamps on receive events. */
    static final String EVENT_RECEIVE = "receive";

    private SignalEvents() {
    }

    /**
     * One complete SSE event: the optional {@code event:} name and the joined {@code data:} payload
     * (multi-line data joined with {@code \n} per the SSE spec; {@code null} when the event carried no
     * data field). Assembled by {@link SseAccumulator}; never Jackson-serialized (no reflection hint).
     */
    record SseEvent(String name, String data) {
    }

    /** What one SSE event means to the channel. A sealed, exhaustive classification. */
    public sealed interface Inbound permits TextMessage, Ignored {
    }

    /**
     * A direct text message the channel drives a turn from.
     *
     * @param replyTo      the id replies are addressed to — the first present of
     *                     {@code sourceNumber}, {@code sourceUuid}, {@code source}.
     * @param sourceNumber the sender's E.164 number, or {@code null} when signal-cli omitted it
     *                     (a UUID-only sender).
     * @param sourceUuid   the sender's Signal UUID, or {@code null} when omitted.
     * @param text         the message body (non-blank).
     * @param timestamp    the {@code dataMessage.timestamp} (epoch millis; {@code 0} when absent).
     */
    public record TextMessage(String replyTo, String sourceNumber, String sourceUuid, String text,
                              long timestamp) implements Inbound {
    }

    /** An event the channel does not act on, with the reason (logged at DEBUG, never content). */
    public record Ignored(String reason) implements Inbound {
    }

    /**
     * Classify one complete SSE event. Total: every input maps to a {@link TextMessage} or an
     * {@link Ignored} — a malformed payload is {@link Ignored}, never a thrown exception, so one bad
     * event cannot kill the stream-consuming loop (the M4 watcher lesson).
     */
    public static Inbound parse(ObjectMapper mapper, SseEvent event) {
        if (event.name() != null && !EVENT_RECEIVE.equals(event.name())) {
            return new Ignored("non-receive event '" + event.name() + "'");
        }
        if (event.data() == null || event.data().isBlank()) {
            return new Ignored("event with no data");
        }
        JsonNode root;
        try {
            root = mapper.readTree(event.data());
        } catch (IOException e) {
            return new Ignored("malformed event JSON");
        }
        if (root == null || !root.isObject()) {
            return new Ignored("malformed event JSON");
        }

        JsonNode payload = root;
        JsonNode method = root.get("method");
        if (method != null && !EVENT_RECEIVE.equals(method.asText())) {
            return new Ignored("non-receive method '" + method.asText() + "'");
        }
        JsonNode params = root.get("params");
        if (params != null && params.isObject()) {
            payload = params; // the JSON-RPC notification wrapping — unwrap to the receive payload
        }

        JsonNode envelope = payload.get("envelope");
        if (envelope == null || !envelope.isObject()) {
            return new Ignored("no envelope");
        }
        // Property EXISTENCE, not truthiness: signal-cli may set syncMessage to null instead of
        // omitting it, and a replayed sentTranscript would loop the bot's own replies.
        if (envelope.has("syncMessage")) {
            return new Ignored("sync message");
        }
        if (envelope.has("receiptMessage")) {
            return new Ignored("receipt");
        }
        if (envelope.has("typingMessage")) {
            return new Ignored("typing notification");
        }

        JsonNode dataMessage = envelope.get("dataMessage");
        if (dataMessage == null || !dataMessage.isObject()) {
            return new Ignored("no data message");
        }
        JsonNode groupInfo = dataMessage.get("groupInfo");
        if (groupInfo != null && !groupInfo.isNull()) {
            return new Ignored("group message (direct messages only in v0.5)");
        }
        String text = textOrNull(dataMessage, "message");
        if (text == null || text.isBlank()) {
            return new Ignored("no message text"); // receipts-by-shape, reactions, attachments-only
        }

        String sourceNumber = textOrNull(envelope, "sourceNumber");
        String sourceUuid = textOrNull(envelope, "sourceUuid");
        String source = textOrNull(envelope, "source");
        String replyTo = sourceNumber != null ? sourceNumber : sourceUuid != null ? sourceUuid : source;
        if (replyTo == null) {
            return new Ignored("no sender");
        }
        long timestamp = dataMessage.path("timestamp").asLong(0L);
        return new TextMessage(replyTo, sourceNumber, sourceUuid, text, timestamp);
    }

    /** The non-blank text of {@code field}, or {@code null} when absent/null/blank. */
    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }
}
