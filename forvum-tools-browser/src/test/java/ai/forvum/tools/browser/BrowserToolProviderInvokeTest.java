package ai.forvum.tools.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.tools.browser.dto.CdpCommand;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Unit tests for {@link BrowserToolProvider#invoke} dispatch (no CDI, no live Chrome): each tool name routes
 * to its {@link BrowserOperations} method, an unknown name and a missing required argument throw, and a CDP
 * failure is caught and relayed as a model-facing "Browser tool error:" string (so a browser failure never
 * crashes the turn — the [M14] graceful-absence contract). Driven by a scripted {@link CdpExecutor} set on
 * the provider's package-private {@code session} field.
 */
class BrowserToolProviderInvokeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A scripted CDP executor mapping each sent command's method → its result node. */
    private static final class FakeCdp implements CdpExecutor {
        private final CdpProtocol protocol = new CdpProtocol(MAPPER);
        private final Function<CdpCommand, JsonNode> script;

        FakeCdp(Function<CdpCommand, JsonNode> script) {
            this.script = script;
        }

        @Override
        public CdpProtocol protocol() {
            return protocol;
        }

        @Override
        public JsonNode send(CdpCommand command) {
            return script.apply(command);
        }

        @Override
        public void clearLoadEvents() {
        }

        @Override
        public boolean loadEventSeen() {
            return true; // settles browser.wait immediately
        }
    }

    private static BrowserToolProvider providerWith(Function<CdpCommand, JsonNode> script) {
        BrowserToolProvider provider = new BrowserToolProvider();
        provider.session = new FakeCdp(script);
        return provider;
    }

    private static JsonNode value(Object v) {
        var inner = MAPPER.createObjectNode();
        inner.set("value", v == null ? MAPPER.nullNode() : MAPPER.valueToTree(v));
        return MAPPER.createObjectNode().set("result", inner);
    }

    private static JsonNode dom(Function<CdpCommand, JsonNode> ignored, CdpCommand c) {
        return switch (c.method()) {
            case "DOM.getDocument" -> MAPPER.createObjectNode()
                    .set("root", MAPPER.valueToTree(Map.of("nodeId", 1)));
            case "DOM.querySelector" -> MAPPER.createObjectNode().put("nodeId", 9);
            case "DOM.getBoxModel" -> MAPPER.createObjectNode().set("model",
                    MAPPER.valueToTree(Map.of("content", List.of(0, 0, 10, 0, 10, 10, 0, 10))));
            default -> MAPPER.createObjectNode();
        };
    }

    @Test
    void navigateDispatches() {
        String out = providerWith(c -> MAPPER.createObjectNode()).invoke("browser.navigate",
                Map.of("url", "https://forvum.ai"));
        assertTrue(out.contains("https://forvum.ai"), out);
    }

    @Test
    void snapshotDispatches() {
        assertEquals("Page text", providerWith(c -> value("Page text")).invoke("browser.snapshot", Map.of()));
    }

    @Test
    void extractDispatches() {
        assertEquals("Hello", providerWith(c -> value("Hello"))
                .invoke("browser.extract", Map.of("selector", "h1")));
    }

    @Test
    void waitDispatches() {
        // loadEventSeen()==true on the fake → browser.wait returns "complete" with no poll.
        assertEquals("complete", providerWith(c -> value("complete")).invoke("browser.wait", Map.of()));
    }

    @Test
    void clickDispatches() {
        String out = providerWith(c -> dom(null, c)).invoke("browser.click", Map.of("selector", "button"));
        assertTrue(out.contains("Clicked"), out);
    }

    @Test
    void typeDispatches() {
        String out = providerWith(c -> dom(null, c))
                .invoke("browser.type", Map.of("selector", "input", "text", "hi"));
        assertTrue(out.contains("Typed 2"), out);
    }

    @Test
    void unknownToolNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> providerWith(c -> MAPPER.createObjectNode()).invoke("browser.unknown", Map.of()));
    }

    @Test
    void missingRequiredArgumentThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> providerWith(c -> MAPPER.createObjectNode()).invoke("browser.navigate", Map.of()));
    }

    @Test
    void aCdpFailureIsRelayedAsAnErrorStringNotThrown() {
        String out = providerWith(c -> {
            throw new CdpException("Chrome is not running.");
        }).invoke("browser.snapshot", Map.of());
        assertTrue(out.startsWith("Browser tool error:"), out);
        assertTrue(out.contains("Chrome is not running"), out);
    }
}
