package ai.forvum.channel.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.forvum.channel.signal.dto.JsonRpcResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * The INBOUND JSON-RPC reply decode pin, mirroring the outbound {@code SignalSendFrameTest}: every send's
 * reply is Jackson-deserialized into {@link JsonRpcResponse}/{@code JsonRpcError} (both carry
 * {@code @RegisterForReflection} for the native binary), yet the {@code RecordingSignalRpcApi} double
 * builds the response in-memory and never an error envelope — so without this test the deserialization
 * path and the result-XOR-error shape are unguarded in the native image. Non-live, no daemon.
 */
class JsonRpcResponseDecodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void anErrorEnvelopeDecodesItsCodeAndMessageWithNullResult() throws Exception {
        JsonRpcResponse response = MAPPER.readValue(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":-32602,\"message\":\"invalid recipient\"}}",
                JsonRpcResponse.class);

        assertNull(response.result(), "an error envelope has no result");
        assertNotNull(response.error(), "the error object decodes");
        assertEquals(-32602, response.error().code().intValue());
        assertEquals("invalid recipient", response.error().message());
    }

    @Test
    void aSuccessEnvelopeDecodesWithNoError() throws Exception {
        JsonRpcResponse response = MAPPER.readValue(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"timestamp\":1700000000000}}",
                JsonRpcResponse.class);

        assertNull(response.error(), "a success envelope has no error");
        assertNotNull(response.result(), "the raw result node is kept");
        assertEquals(1700000000000L, response.result().get("timestamp").asLong());
    }

    @Test
    void unknownFieldsAreIgnored() throws Exception {
        // @JsonIgnoreProperties(ignoreUnknown = true): the daemon may add fields across versions.
        JsonRpcResponse response = MAPPER.readValue(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{},\"aFutureField\":\"x\"}",
                JsonRpcResponse.class);

        assertNull(response.error());
        assertNotNull(response.result());
    }
}
