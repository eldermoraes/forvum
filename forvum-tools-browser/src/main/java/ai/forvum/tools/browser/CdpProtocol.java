package ai.forvum.tools.browser;

import ai.forvum.tools.browser.dto.CdpCommand;
import ai.forvum.tools.browser.dto.CdpMessage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure (socket-free) Chrome DevTools Protocol (CDP) wire logic: a monotonic command-id allocator, frame
 * encode ({@link #encodeCommand}) / decode ({@link #parse}), and the per-command builders
 * ({@code Page.navigate}, {@code Runtime.evaluate}, {@code DOM.getDocument}/{@code DOM.querySelector}/
 * {@code DOM.getBoxModel}, {@code Input.dispatchMouseEvent}/{@code Input.dispatchKeyEvent},
 * {@code Page.enable}). Keeping this layer free of any WebSocket dependency is what makes the command
 * shapes unit-testable without a live Chrome (mirrors the discord {@code GatewayProtocol} pattern).
 *
 * <p>All methods read/build values only; {@link #nextId()} mutates a single {@link AtomicLong}. No IO, no
 * lock — safe to call from an inbound-frame virtual thread.
 */
public final class CdpProtocol {

    private final ObjectMapper mapper;
    private final AtomicLong idSeq = new AtomicLong(0);

    public CdpProtocol(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** The next monotonic command id (starts at 1). */
    public long nextId() {
        return idSeq.incrementAndGet();
    }

    // --- frame encode / decode -------------------------------------------------------------------

    /**
     * Encode a {@link CdpCommand} to its JSON wire frame. EVERY outbound command goes through this record
     * + {@link ObjectMapper#writeValueAsString}, so {@link CdpCommand} carries the real
     * {@code @RegisterForReflection} (the [P2-CH/discord] native trap); without it the native binary emits
     * a malformed/empty frame and CDP silently no-ops.
     */
    public String encodeCommand(CdpCommand command) {
        try {
            return mapper.writeValueAsString(command);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode CDP command frame.", e);
        }
    }

    /** Build + encode a command in one step (allocating a fresh id), returning both id and frame. */
    public Encoded command(String method, JsonNode params) {
        long id = nextId();
        return new Encoded(id, encodeCommand(new CdpCommand(id, method, params)));
    }

    /** An encoded command frame paired with the id its response will carry. */
    public record Encoded(long id, String frame) {
    }

    /** Parse a raw inbound CDP text frame into the generic {@link CdpMessage} envelope. */
    public CdpMessage parse(String frame) {
        try {
            return mapper.readValue(frame, CdpMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unparseable CDP frame.", e);
        }
    }

    // --- command builders (params nodes) ---------------------------------------------------------

    /** {@code Page.navigate} to {@code url}. */
    public CdpCommand pageNavigate(String url) {
        ObjectNode params = mapper.createObjectNode();
        params.put("url", url);
        return new CdpCommand(nextId(), "Page.navigate", params);
    }

    /** {@code Page.enable} — required before {@code Page.*} events such as {@code Page.loadEventFired}. */
    public CdpCommand pageEnable() {
        return new CdpCommand(nextId(), "Page.enable", mapper.createObjectNode());
    }

    /**
     * {@code Runtime.evaluate} of {@code expression} with {@code returnByValue=true} so the result is a
     * JSON scalar (no {@code RemoteObject} handle to dereference) and {@code awaitPromise=true} so an
     * async expression resolves before the response.
     */
    public CdpCommand runtimeEvaluate(String expression) {
        ObjectNode params = mapper.createObjectNode();
        params.put("expression", expression);
        params.put("returnByValue", true);
        params.put("awaitPromise", true);
        return new CdpCommand(nextId(), "Runtime.evaluate", params);
    }

    /** {@code DOM.getDocument} (depth 0) — yields the root node id used by {@link #domQuerySelector}. */
    public CdpCommand domGetDocument() {
        ObjectNode params = mapper.createObjectNode();
        params.put("depth", 0);
        return new CdpCommand(nextId(), "DOM.getDocument", params);
    }

    /** {@code DOM.querySelector} of {@code selector} under {@code nodeId}. */
    public CdpCommand domQuerySelector(long nodeId, String selector) {
        ObjectNode params = mapper.createObjectNode();
        params.put("nodeId", nodeId);
        params.put("selector", selector);
        return new CdpCommand(nextId(), "DOM.querySelector", params);
    }

    /** {@code DOM.getBoxModel} for {@code nodeId} — yields the content-quad used to compute a click point. */
    public CdpCommand domGetBoxModel(long nodeId) {
        ObjectNode params = mapper.createObjectNode();
        params.put("nodeId", nodeId);
        return new CdpCommand(nextId(), "DOM.getBoxModel", params);
    }

    /** {@code DOM.focus} on {@code nodeId} — focus the element before typing characters into it. */
    public CdpCommand domFocus(long nodeId) {
        ObjectNode params = mapper.createObjectNode();
        params.put("nodeId", nodeId);
        return new CdpCommand(nextId(), "DOM.focus", params);
    }

    /**
     * {@code Input.dispatchMouseEvent} of {@code type} ({@code mousePressed}/{@code mouseReleased}) at
     * {@code (x, y)} with the left button and a single click — the press/release pair forms a click.
     */
    public CdpCommand inputDispatchMouseEvent(String type, double x, double y) {
        ObjectNode params = mapper.createObjectNode();
        params.put("type", type);
        params.put("x", x);
        params.put("y", y);
        params.put("button", "left");
        params.put("buttons", 1);
        params.put("clickCount", 1);
        return new CdpCommand(nextId(), "Input.dispatchMouseEvent", params);
    }

    /**
     * {@code Input.dispatchKeyEvent} of {@code type} ({@code char}) inserting {@code text}. The
     * {@code char} event type inserts the printable text the way real typing would (no virtual-key
     * mapping needed for plain text input).
     */
    public CdpCommand inputDispatchKeyEvent(String type, String text) {
        ObjectNode params = mapper.createObjectNode();
        params.put("type", type);
        params.put("text", text);
        return new CdpCommand(nextId(), "Input.dispatchKeyEvent", params);
    }
}
