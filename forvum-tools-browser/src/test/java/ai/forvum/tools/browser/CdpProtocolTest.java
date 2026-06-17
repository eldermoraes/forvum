package ai.forvum.tools.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.tools.browser.dto.CdpCommand;
import ai.forvum.tools.browser.dto.CdpMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Socket-free unit tests for the pure {@link CdpProtocol}: the monotonic id allocator, frame encode/decode,
 * and every command builder's wire shape — exercised with no live Chrome (the P2-1 testability
 * requirement, mirroring the discord {@code GatewayProtocolTest}).
 *
 * <p>The {@link #commandFrameIsWellFormedWithIdMethodAndParams} test is the MANDATORY [P2-CH/discord]
 * outbound-frame reflection-trap pin: in a native binary an un-hinted record serializes to an
 * empty/malformed frame and CDP silently no-ops — and the no-config native smoke never serializes a frame
 * (no Chrome attached), so it cannot catch the omission.
 */
class CdpProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CdpProtocol protocol() {
        return new CdpProtocol(MAPPER);
    }

    @Test
    void nextIdIsMonotonicStartingAtOne() {
        CdpProtocol protocol = protocol();
        assertEquals(1L, protocol.nextId());
        assertEquals(2L, protocol.nextId());
        assertEquals(3L, protocol.nextId());
    }

    @Test
    void commandFrameIsWellFormedWithIdMethodAndParams() throws Exception {
        // The outbound-frame envelope (CdpCommand carrying @RegisterForReflection): the native trap. Assert
        // an encoded command carries id/method/params with the right values so a missing-reflection
        // regression (an empty/malformed frame on native) is caught here, not silently in production.
        CdpProtocol protocol = protocol();
        JsonNode params = MAPPER.createObjectNode().put("url", "https://example.com");
        CdpCommand command = new CdpCommand(7L, "Page.navigate", params);

        JsonNode frame = MAPPER.readTree(protocol.encodeCommand(command));

        assertTrue(frame.has("id"), "the command frame carries an \"id\" field");
        assertTrue(frame.has("method"), "the command frame carries a \"method\" field");
        assertTrue(frame.has("params"), "the command frame carries a \"params\" field");
        assertEquals(7L, frame.get("id").asLong());
        assertEquals("Page.navigate", frame.get("method").asText());
        assertTrue(frame.get("params").isObject(), "params is the (object) payload, not empty");
        assertEquals("https://example.com", frame.get("params").get("url").asText());
    }

    @Test
    void aSessionlessCommandOmitsSessionIdFromTheWire() throws Exception {
        JsonNode frame = MAPPER.readTree(protocol().encodeCommand(
                new CdpCommand(1L, "Page.enable", MAPPER.createObjectNode())));
        assertFalse(frame.has("sessionId"), "a null sessionId is omitted (NON_NULL), not emitted as null");
    }

    @Test
    void commandHelperAllocatesAnIdAndPairsItWithTheFrame() throws Exception {
        CdpProtocol protocol = protocol();
        CdpProtocol.Encoded encoded = protocol.command("Page.enable", MAPPER.createObjectNode());

        assertEquals(1L, encoded.id(), "the helper allocates the next monotonic id");
        assertEquals(1L, MAPPER.readTree(encoded.frame()).get("id").asLong(),
                "the frame's id matches the returned correlation id");
    }

    @Test
    void pageNavigateBuilderCarriesTheUrl() {
        CdpCommand command = protocol().pageNavigate("https://forvum.ai");
        assertEquals("Page.navigate", command.method());
        assertEquals("https://forvum.ai", command.params().get("url").asText());
    }

    @Test
    void runtimeEvaluateReturnsByValueAndAwaitsPromises() {
        CdpCommand command = protocol().runtimeEvaluate("document.title");
        assertEquals("Runtime.evaluate", command.method());
        assertEquals("document.title", command.params().get("expression").asText());
        assertTrue(command.params().get("returnByValue").asBoolean(),
                "returnByValue keeps the result a JSON scalar — no RemoteObject handle to dereference");
        assertTrue(command.params().get("awaitPromise").asBoolean());
    }

    @Test
    void domQuerySelectorCarriesNodeIdAndSelector() {
        CdpCommand command = protocol().domQuerySelector(42L, "button.submit");
        assertEquals("DOM.querySelector", command.method());
        assertEquals(42L, command.params().get("nodeId").asLong());
        assertEquals("button.submit", command.params().get("selector").asText());
    }

    @Test
    void domGetBoxModelCarriesNodeId() {
        CdpCommand command = protocol().domGetBoxModel(99L);
        assertEquals("DOM.getBoxModel", command.method());
        assertEquals(99L, command.params().get("nodeId").asLong());
    }

    @Test
    void inputDispatchMouseEventCarriesTypeCoordinatesAndLeftButton() {
        CdpCommand command = protocol().inputDispatchMouseEvent("mousePressed", 10.5, 20.5);
        assertEquals("Input.dispatchMouseEvent", command.method());
        assertEquals("mousePressed", command.params().get("type").asText());
        assertEquals(10.5, command.params().get("x").asDouble());
        assertEquals(20.5, command.params().get("y").asDouble());
        assertEquals("left", command.params().get("button").asText());
        assertEquals(1, command.params().get("clickCount").asInt());
    }

    @Test
    void inputDispatchKeyEventCarriesTheCharText() {
        CdpCommand command = protocol().inputDispatchKeyEvent("char", "a");
        assertEquals("Input.dispatchKeyEvent", command.method());
        assertEquals("char", command.params().get("type").asText());
        assertEquals("a", command.params().get("text").asText());
    }

    @Test
    void everyBuiltCommandGetsADistinctMonotonicId() {
        CdpProtocol protocol = protocol();
        long a = protocol.pageNavigate("https://a").id();
        long b = protocol.pageEnable().id();
        long c = protocol.runtimeEvaluate("1").id();
        assertNotEquals(a, b);
        assertNotEquals(b, c);
        assertTrue(b > a && c > b, "ids are strictly increasing");
    }

    @Test
    void parseDecodesACommandResponse() {
        CdpMessage message = protocol().parse("{\"id\":5,\"result\":{\"frameId\":\"F1\"}}");
        assertTrue(message.isResponse(), "a frame with an id is a command response");
        assertFalse(message.isError());
        assertEquals(5L, message.id());
        assertEquals("F1", message.result().get("frameId").asText());
    }

    @Test
    void parseDecodesAnErrorResponse() {
        CdpMessage message = protocol().parse(
                "{\"id\":6,\"error\":{\"code\":-32000,\"message\":\"Cannot navigate\"}}");
        assertTrue(message.isResponse());
        assertTrue(message.isError(), "a response carrying an error payload is an error");
        assertEquals("Cannot navigate", message.error().get("message").asText());
    }

    @Test
    void parseDecodesAnUnsolicitedEvent() {
        CdpMessage message = protocol().parse(
                "{\"method\":\"Page.loadEventFired\",\"params\":{\"timestamp\":12.3}}");
        assertFalse(message.isResponse(), "an event carries no id");
        assertEquals("Page.loadEventFired", message.method());
        assertEquals(12.3, message.params().get("timestamp").asDouble());
    }

    @Test
    void parseToleratesUnknownInboundFields() {
        // @JsonIgnoreProperties(ignoreUnknown=true): a future protocol field must not break decoding.
        CdpMessage message = protocol().parse(
                "{\"id\":1,\"result\":{},\"sessionId\":\"S\",\"futureField\":true}");
        assertEquals(1L, message.id());
    }
}
