package ai.forvum.channel.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.signal.dto.JsonRpcRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * The OUTBOUND send-frame encode pin (the Discord NATIVE-FRAME lesson): every reply rides a
 * Jackson-serialized {@link JsonRpcRequest}, and the no-config native smoke never serializes one (no
 * daemon → no send), so a missing {@code @RegisterForReflection} would surface only as a silently
 * empty/malformed frame in the native binary. This non-live test pins the exact JSON-RPC 2.0 wire shape
 * — {@code jsonrpc}/{@code id}/{@code method}/{@code params.account}/{@code params.recipient[]}/
 * {@code params.message} — so the encode path is exercised and the shape cannot drift.
 */
class SignalSendFrameTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aSendRequestEncodesTheJsonRpc2SendShape() throws Exception {
        JsonRpcRequest request =
                JsonRpcRequest.send(7L, "+15559990000", "+15550001111", "hello back");

        JsonNode root = MAPPER.readTree(MAPPER.writeValueAsString(request));

        assertEquals("2.0", root.get("jsonrpc").asText());
        assertEquals(7L, root.get("id").asLong());
        assertEquals("send", root.get("method").asText());
        JsonNode params = root.get("params");
        assertEquals("+15559990000", params.get("account").asText());
        assertTrue(params.get("recipient").isArray(), "recipient is a JSON ARRAY");
        assertEquals(1, params.get("recipient").size());
        assertEquals("+15550001111", params.get("recipient").get(0).asText());
        assertEquals("hello back", params.get("message").asText());
    }

    @Test
    void aUuidRecipientRidesTheSameArray() throws Exception {
        JsonRpcRequest request = JsonRpcRequest.send(8L, "+15559990000",
                "9d3f5c8e-0000-4e2a-9aa3-2f37d1f3a111", "to a uuid");

        JsonNode root = MAPPER.readTree(MAPPER.writeValueAsString(request));

        assertEquals("9d3f5c8e-0000-4e2a-9aa3-2f37d1f3a111",
                root.get("params").get("recipient").get(0).asText());
    }
}
