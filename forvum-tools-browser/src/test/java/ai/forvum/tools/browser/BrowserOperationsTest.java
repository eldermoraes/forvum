package ai.forvum.tools.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.tools.browser.dto.CdpCommand;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Unit tests for {@link BrowserOperations} against a scripted {@link FakeCdpExecutor} (no live Chrome): each
 * tool's CDP command sequence and its result interpretation. This is where the per-tool orchestration is
 * exercised; {@link CdpSession}'s live dial path is IT/live-only (jacoco-excluded).
 */
class BrowserOperationsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A scripted CDP executor: a function maps each sent command's method → its result node. */
    static final class FakeCdpExecutor implements CdpExecutor {
        private final CdpProtocol protocol = new CdpProtocol(MAPPER);
        final List<String> sentMethods = new ArrayList<>();
        final List<CdpCommand> sent = new ArrayList<>();
        private final Function<CdpCommand, JsonNode> script;
        boolean loadEvent;

        FakeCdpExecutor(Function<CdpCommand, JsonNode> script) {
            this.script = script;
        }

        @Override
        public CdpProtocol protocol() {
            return protocol;
        }

        @Override
        public JsonNode send(CdpCommand command) {
            sentMethods.add(command.method());
            sent.add(command);
            return script.apply(command);
        }

        @Override
        public void clearLoadEvents() {
            loadEvent = false;
        }

        @Override
        public boolean loadEventSeen() {
            return loadEvent;
        }
    }

    private static JsonNode evaluateValue(Object value) {
        // A Runtime.evaluate response: { "result": { "value": <value> } }. Built field-by-field (not via
        // Map.of, which is null-hostile) so a JS `null` value — the missing-element case — is representable.
        com.fasterxml.jackson.databind.node.ObjectNode inner = MAPPER.createObjectNode();
        inner.set("value", value == null ? MAPPER.nullNode() : MAPPER.valueToTree(value));
        return MAPPER.createObjectNode().set("result", inner);
    }

    private static JsonNode emptyResult() {
        return MAPPER.createObjectNode();
    }

    @Test
    void navigateEnablesPageThenNavigatesAndConfirms() {
        FakeCdpExecutor cdp = new FakeCdpExecutor(c -> emptyResult());
        String out = new BrowserOperations(cdp).navigate("https://forvum.ai");

        assertEquals(List.of("Page.enable", "Page.navigate"), cdp.sentMethods);
        assertEquals("https://forvum.ai",
                cdp.sent.get(1).params().get("url").asText(), "the navigate carries the url");
        assertTrue(out.contains("https://forvum.ai"));
    }

    @Test
    void navigateThatReturnsAnErrorTextThrows() {
        FakeCdpExecutor cdp = new FakeCdpExecutor(c -> c.method().equals("Page.navigate")
                ? MAPPER.createObjectNode().put("errorText", "net::ERR_NAME_NOT_RESOLVED")
                : emptyResult());
        CdpException e = assertThrows(CdpException.class,
                () -> new BrowserOperations(cdp).navigate("https://nope.invalid"));
        assertTrue(e.getMessage().contains("ERR_NAME_NOT_RESOLVED"));
    }

    @Test
    void snapshotReturnsTheInnerText() {
        FakeCdpExecutor cdp = new FakeCdpExecutor(c -> evaluateValue("Hello world"));
        assertEquals("Hello world", new BrowserOperations(cdp).snapshot());
        assertEquals(List.of("Runtime.evaluate"), cdp.sentMethods);
    }

    @Test
    void extractReturnsTheSelectorTextContent() {
        FakeCdpExecutor cdp = new FakeCdpExecutor(c -> evaluateValue("Sign in"));
        assertEquals("Sign in", new BrowserOperations(cdp).extract("button.login"));
        assertTrue(cdp.sent.get(0).params().get("expression").asText().contains("button.login"),
                "the selector is interpolated into the evaluated expression");
    }

    @Test
    void extractOnAMissingElementReturnsANote() {
        FakeCdpExecutor cdp = new FakeCdpExecutor(c -> evaluateValue(null));
        String out = new BrowserOperations(cdp).extract(".does-not-exist");
        assertTrue(out.contains("No element matched"), out);
    }

    @Test
    void aPageScriptExceptionSurfacesAsACdpException() {
        FakeCdpExecutor cdp = new FakeCdpExecutor(c -> MAPPER.createObjectNode()
                .set("exceptionDetails", MAPPER.valueToTree(java.util.Map.of("text", "ReferenceError: x"))));
        CdpException e = assertThrows(CdpException.class, () -> new BrowserOperations(cdp).snapshot());
        assertTrue(e.getMessage().contains("ReferenceError"));
    }

    @Test
    void clickResolvesTheNodeReadsItsBoxAndDispatchesPressRelease() {
        FakeCdpExecutor cdp = new FakeCdpExecutor(c -> switch (c.method()) {
            case "DOM.getDocument" -> MAPPER.createObjectNode()
                    .set("root", MAPPER.valueToTree(java.util.Map.of("nodeId", 1)));
            case "DOM.querySelector" -> MAPPER.createObjectNode().put("nodeId", 5);
            case "DOM.getBoxModel" -> MAPPER.createObjectNode().set("model",
                    MAPPER.valueToTree(java.util.Map.of("content",
                            List.of(10, 20, 30, 20, 30, 40, 10, 40))));
            default -> emptyResult();
        });

        String out = new BrowserOperations(cdp).click("button.submit");

        assertEquals(List.of("DOM.getDocument", "DOM.querySelector", "DOM.getBoxModel",
                "Input.dispatchMouseEvent", "Input.dispatchMouseEvent"), cdp.sentMethods);
        // centre of [10,20,30,20,30,40,10,40] = (20, 30)
        CdpCommand press = cdp.sent.get(3);
        assertEquals(20.0, press.params().get("x").asDouble());
        assertEquals(30.0, press.params().get("y").asDouble());
        assertEquals("mousePressed", press.params().get("type").asText());
        assertEquals("mouseReleased", cdp.sent.get(4).params().get("type").asText());
        assertTrue(out.contains("button.submit"));
    }

    @Test
    void clickOnAMissingElementThrows() {
        FakeCdpExecutor cdp = new FakeCdpExecutor(c -> switch (c.method()) {
            case "DOM.getDocument" -> MAPPER.createObjectNode()
                    .set("root", MAPPER.valueToTree(java.util.Map.of("nodeId", 1)));
            case "DOM.querySelector" -> MAPPER.createObjectNode().put("nodeId", 0); // 0 == not found
            default -> emptyResult();
        });
        CdpException e = assertThrows(CdpException.class,
                () -> new BrowserOperations(cdp).click(".missing"));
        assertTrue(e.getMessage().contains("No element matched"));
    }

    @Test
    void clickOnAnElementWithNoLayoutBoxThrows() {
        FakeCdpExecutor cdp = new FakeCdpExecutor(c -> switch (c.method()) {
            case "DOM.getDocument" -> MAPPER.createObjectNode()
                    .set("root", MAPPER.valueToTree(java.util.Map.of("nodeId", 1)));
            case "DOM.querySelector" -> MAPPER.createObjectNode().put("nodeId", 5);
            case "DOM.getBoxModel" -> emptyResult(); // no model → hidden element
            default -> emptyResult();
        });
        CdpException e = assertThrows(CdpException.class,
                () -> new BrowserOperations(cdp).click("#hidden"));
        assertTrue(e.getMessage().contains("no layout box"));
    }

    @Test
    void typeFocusesTheNodeThenDispatchesOneCharEventPerCharacter() {
        FakeCdpExecutor cdp = new FakeCdpExecutor(c -> switch (c.method()) {
            case "DOM.getDocument" -> MAPPER.createObjectNode()
                    .set("root", MAPPER.valueToTree(java.util.Map.of("nodeId", 1)));
            case "DOM.querySelector" -> MAPPER.createObjectNode().put("nodeId", 7);
            default -> emptyResult();
        });

        String out = new BrowserOperations(cdp).type("input#q", "hi");

        assertEquals(List.of("DOM.getDocument", "DOM.querySelector", "DOM.focus",
                "Input.dispatchKeyEvent", "Input.dispatchKeyEvent"), cdp.sentMethods);
        assertEquals("h", cdp.sent.get(3).params().get("text").asText());
        assertEquals("i", cdp.sent.get(4).params().get("text").asText());
        assertTrue(out.contains("2 character"));
    }

    @Test
    void waitReturnsCompleteWhenReadyStateIsComplete() {
        FakeCdpExecutor cdp = new FakeCdpExecutor(c -> evaluateValue("complete"));
        assertEquals("complete", new BrowserOperations(cdp).waitForLoad(3, 1));
    }

    @Test
    void waitShortCircuitsOnABufferedLoadEvent() {
        FakeCdpExecutor cdp = new FakeCdpExecutor(c -> evaluateValue("loading"));
        cdp.loadEvent = true;
        assertEquals("complete", new BrowserOperations(cdp).waitForLoad(3, 1),
                "a buffered Page.loadEventFired settles the wait without polling readyState");
        assertTrue(cdp.sentMethods.isEmpty(), "no readyState poll was needed");
    }

    @Test
    void waitReturnsTheLastReadyStateAfterExhaustingPolls() {
        FakeCdpExecutor cdp = new FakeCdpExecutor(c -> evaluateValue("loading"));
        assertEquals("loading", new BrowserOperations(cdp).waitForLoad(2, 1),
                "after exhausting polls the last observed readyState is returned");
    }

    @Test
    void navigateWithANullResultStillConfirms() {
        // Page.navigate may return no body (a null result node) — the errorText branch must tolerate null.
        FakeCdpExecutor cdp = new FakeCdpExecutor(c -> null);
        assertTrue(new BrowserOperations(cdp).navigate("https://x").contains("https://x"));
    }

    @Test
    void navigateWithABlankErrorTextStillConfirms() {
        FakeCdpExecutor cdp = new FakeCdpExecutor(c -> c.method().equals("Page.navigate")
                ? MAPPER.createObjectNode().put("errorText", "   ")
                : emptyResult());
        assertTrue(new BrowserOperations(cdp).navigate("https://x").contains("https://x"),
                "a blank errorText is not a failure");
    }

    @Test
    void snapshotWithANullEvaluateResultFallsBackToEmpty() {
        FakeCdpExecutor cdp = new FakeCdpExecutor(c -> null);
        assertEquals("", new BrowserOperations(cdp).snapshot(),
                "a null Runtime.evaluate result falls back to empty text");
    }

    @Test
    void snapshotWithAnEmptyResultObjectFallsBackToEmpty() {
        // result present but no inner "result"/"value" node → fallback "" (the resultValue null-inner branch).
        FakeCdpExecutor cdp = new FakeCdpExecutor(c -> emptyResult());
        assertEquals("", new BrowserOperations(cdp).snapshot());
    }

    @Test
    void clickWhenTheDocumentRootCannotBeReadThrows() {
        FakeCdpExecutor cdp = new FakeCdpExecutor(c -> emptyResult()); // no "root" node
        CdpException e = assertThrows(CdpException.class,
                () -> new BrowserOperations(cdp).click("button"));
        assertTrue(e.getMessage().contains("document root"), e.getMessage());
    }
}
