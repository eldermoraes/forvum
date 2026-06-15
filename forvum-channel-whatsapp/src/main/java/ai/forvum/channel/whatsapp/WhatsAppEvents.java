package ai.forvum.channel.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure (socket-free) WhatsApp Cloud API webhook protocol logic: extract the inbound TEXT messages from a
 * Meta {@code messages} webhook payload. Keeping this layer free of any HTTP dependency is what makes the
 * receive flow unit-testable with recorded payloads and no live Meta callback (mirrors the Signal
 * channel's {@code SignalEvents} and the Discord channel's {@code GatewayProtocol}).
 *
 * <p>A WhatsApp webhook POST batches events under {@code entry[].changes[].value}: the {@code messages}
 * array carries inbound user messages, the {@code statuses} array carries delivery/read receipts for the
 * business's own sends. Only {@code type == "text"} messages with a non-blank {@code text.body} are
 * returned (v0.5 scope) — statuses, non-text messages (image/audio/location/…), reactions, and malformed
 * payloads yield an EMPTY list, never a thrown exception, so one bad payload cannot kill the webhook.
 * The business never receives its OWN sends as inbound {@code messages}, so (unlike Signal) there is no
 * self-echo loop to guard against.
 */
public final class WhatsAppEvents {

    private WhatsAppEvents() {
    }

    /**
     * One inbound WhatsApp text message.
     *
     * @param from      the sender's WhatsApp id ({@code wa_id}, a phone number without {@code +}) — the
     *                  reply is addressed to it and it is checked against {@code allowedUserIds}.
     * @param text      the message body (non-blank).
     * @param messageId the {@code wamid} message id (for diagnostics; never the content).
     * @param timestamp the message timestamp (epoch seconds; {@code 0} when absent/unparseable).
     */
    public record InboundMessage(String from, String text, String messageId, long timestamp) {
    }

    /**
     * Extract every inbound text message from a webhook POST body. Total: a malformed body, a
     * status-only notification, or a payload with no text messages all return an empty list.
     */
    public static List<InboundMessage> parse(ObjectMapper mapper, String body) {
        List<InboundMessage> messages = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return messages;
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            return messages; // malformed JSON → nothing to process (never throw)
        }
        if (root == null || !root.isObject()) {
            return messages;
        }
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                for (JsonNode message : value.path("messages")) {
                    InboundMessage parsed = parseMessage(message);
                    if (parsed != null) {
                        messages.add(parsed);
                    }
                }
            }
        }
        return messages;
    }

    /** One {@code messages[]} element → an {@link InboundMessage}, or {@code null} when not consumable. */
    private static InboundMessage parseMessage(JsonNode message) {
        if (message == null || !message.isObject()) {
            return null;
        }
        if (!"text".equals(textOrNull(message, "type"))) {
            return null; // non-text (image/audio/location/reaction/…) is out of scope in v0.5
        }
        String text = textOrNull(message.path("text"), "body");
        String from = textOrNull(message, "from");
        if (text == null || text.isBlank() || from == null) {
            return null;
        }
        long timestamp = parseEpochSeconds(textOrNull(message, "timestamp"));
        return new InboundMessage(from, text, textOrNull(message, "id"), timestamp);
    }

    /** The non-blank text of {@code field} on {@code node}, or {@code null} when absent/null/blank. */
    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    /** WhatsApp stamps the timestamp as a string of epoch SECONDS; {@code 0} on absent/unparseable. */
    private static long parseEpochSeconds(String raw) {
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
