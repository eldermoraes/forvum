package ai.forvum.channel.matrix.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The request body for the Matrix {@code PUT /_matrix/client/v3/rooms/{roomId}/send/m.room.message/
 * {txnId}} endpoint: a JSON object {@code { "msgtype": "m.text", "body": "<text>" }}. Forvum sends only
 * plain text (no formatted_body, no relations) in v0.x.
 *
 * <p><strong>OUTBOUND frame — the native-frame trap.</strong> This record is Jackson-SERIALIZED by the
 * REST client, so it MUST carry a real {@code @RegisterForReflection}: without it the native binary
 * emits an empty {@code {}} body and every reply silently fails — and the no-credentials native smoke
 * cannot catch it (no token → never serialized). {@code OutboundFrameEncodeTest} pins the encoded shape.
 */
@RegisterForReflection
public record SendMessageRequest(String msgtype, String body) {

    /** The {@code m.text} message body Forvum sends ({@code { "msgtype": "m.text", "body": text }}). */
    public static SendMessageRequest text(String body) {
        return new SendMessageRequest("m.text", body);
    }
}
