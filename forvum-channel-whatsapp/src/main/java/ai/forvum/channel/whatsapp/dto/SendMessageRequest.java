package ai.forvum.channel.whatsapp.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The Graph API {@code POST {phone-number-id}/messages} request body for a text reply
 * ({@code { "messaging_product": "whatsapp", "to": "<wa_id>", "type": "text", "text": { "body": "..." } }}).
 * Jackson-SERIALIZED on every reply — hence the {@code @RegisterForReflection} on this record AND its
 * nested {@link TextBody} (the OUTBOUND native-frame rule: the no-config native smoke never serializes one,
 * so a missing hint would surface only as a silently empty/malformed frame in the native binary — the
 * Discord NATIVE-FRAME TRAP). The component names are the exact wire field names.
 */
@RegisterForReflection
public record SendMessageRequest(String messaging_product, String to, String type, TextBody text) {

    /** The {@code text} object of a WhatsApp text message: {@code { "body": "..." }}. */
    @RegisterForReflection
    public record TextBody(String body) {
    }

    /** Build a text-message send to {@code to} (a WhatsApp {@code wa_id}) carrying {@code body}. */
    public static SendMessageRequest text(String to, String body) {
        return new SendMessageRequest("whatsapp", to, "text", new TextBody(body));
    }
}
