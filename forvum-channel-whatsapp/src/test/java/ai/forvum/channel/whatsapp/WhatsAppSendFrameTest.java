package ai.forvum.channel.whatsapp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.forvum.channel.whatsapp.dto.SendMessageRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * The OUTBOUND Graph-send-frame encode pin (the Discord NATIVE-FRAME lesson): every reply rides a
 * Jackson-serialized {@link SendMessageRequest}, and the no-config native smoke never serializes one (no
 * credentials → no send), so a missing {@code @RegisterForReflection} would surface only as a silently
 * empty/malformed frame in the native binary. This non-live test pins the exact Cloud API wire shape —
 * {@code messaging_product}/{@code to}/{@code type}/{@code text.body} — so the encode path is exercised
 * and the shape cannot drift.
 */
class WhatsAppSendFrameTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aTextSendEncodesTheCloudApiShape() throws Exception {
        SendMessageRequest request = SendMessageRequest.text("15550001111", "hello back");

        JsonNode root = MAPPER.readTree(MAPPER.writeValueAsString(request));

        assertEquals("whatsapp", root.get("messaging_product").asText());
        assertEquals("15550001111", root.get("to").asText());
        assertEquals("text", root.get("type").asText());
        assertEquals("hello back", root.get("text").get("body").asText());
    }
}
