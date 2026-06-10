package ai.forvum.channel.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.forvum.channel.matrix.dto.JoinRequest;
import ai.forvum.channel.matrix.dto.SendMessageRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Pins the OUTBOUND wire shape of the Jackson-serialized request bodies (the Discord NATIVE-FRAME trap):
 * a missing {@code @RegisterForReflection} on an outbound record makes the native binary emit an
 * empty/malformed body, and the no-credentials native smoke can NOT catch it (no token → never
 * serialized). This non-live encode test asserts the serialized JSON carries the right fields, so a
 * field rename or an accessor regression is caught here.
 */
class OutboundFrameEncodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sendMessageRequestEncodesMsgtypeAndBody() throws Exception {
        JsonNode frame = MAPPER.readTree(MAPPER.writeValueAsString(SendMessageRequest.text("hello")));

        assertEquals(2, frame.size(), "exactly msgtype + body");
        assertEquals("m.text", frame.get("msgtype").asText());
        assertEquals("hello", frame.get("body").asText());
    }

    @Test
    void joinRequestEncodesAsTheEmptyJsonObject() throws Exception {
        assertEquals("{}", MAPPER.writeValueAsString(new JoinRequest()));
    }
}
