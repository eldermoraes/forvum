package ai.forvum.tools.browser;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The per-tool CDP orchestration (P2-1, #26), driven through the {@link CdpExecutor} seam so every
 * operation is unit-testable against a scripted fake with no live Chrome. Each method maps one tool call
 * onto its CDP command sequence and interprets the JSON result into a model-facing string; a CDP failure
 * surfaces as a {@link CdpException} the {@link BrowserToolProvider} turns into an error string.
 *
 * <ul>
 *   <li>{@code navigate} = {@code Page.enable} + {@code Page.navigate}, then a short readyState settle.</li>
 *   <li>{@code snapshot} = {@code Runtime.evaluate(document.body.innerText)} ({@code returnByValue}).</li>
 *   <li>{@code extract} = {@code Runtime.evaluate(querySelector(sel)?.textContent)}.</li>
 *   <li>{@code click} = {@code DOM.getDocument} → {@code DOM.querySelector} → {@code DOM.getBoxModel} →
 *       {@code Input.dispatchMouseEvent} (mousePressed + mouseReleased) at the box centre.</li>
 *   <li>{@code type} = {@code DOM.getDocument} → {@code DOM.querySelector} → {@code DOM.focus} →
 *       one {@code Input.dispatchKeyEvent(char)} per character.</li>
 *   <li>{@code wait} = poll {@code Runtime.evaluate(document.readyState)} until {@code complete} (or the
 *       buffered {@code Page.loadEventFired}).</li>
 * </ul>
 */
public final class BrowserOperations {

    private final CdpExecutor cdp;

    public BrowserOperations(CdpExecutor cdp) {
        this.cdp = cdp;
    }

    /** Navigate to {@code url}; returns a short confirmation with the final frame's URL where available. */
    public String navigate(String url) {
        cdp.send(cdp.protocol().pageEnable());
        JsonNode result = cdp.send(cdp.protocol().pageNavigate(url));
        JsonNode error = result == null ? null : result.get("errorText");
        if (error != null && !error.asText().isBlank()) {
            throw new CdpException("Navigation to " + url + " failed: " + error.asText() + ".");
        }
        return "Navigated to " + url + ".";
    }

    /** The visible text of the current page ({@code document.body.innerText}). */
    public String snapshot() {
        JsonNode result = cdp.send(cdp.protocol().runtimeEvaluate(
                "document.body ? document.body.innerText : ''"));
        return evaluateString(result, "");
    }

    /** The text content of the first element matching {@code selector}; an absent element yields a note. */
    public String extract(String selector) {
        String expression = "(function(){var e=document.querySelector(" + jsString(selector)
                + ");return e?e.textContent:null;})()";
        JsonNode result = cdp.send(cdp.protocol().runtimeEvaluate(expression));
        JsonNode value = resultValue(result);
        if (value == null || value.isNull()) {
            return "No element matched the selector '" + selector + "'.";
        }
        return value.asText();
    }

    /** Click the first element matching {@code selector} (mousePressed + mouseReleased at its centre). */
    public String click(String selector) {
        long nodeId = resolveNode(selector);
        double[] centre = boxCentre(nodeId, selector);
        cdp.send(cdp.protocol().inputDispatchMouseEvent("mousePressed", centre[0], centre[1]));
        cdp.send(cdp.protocol().inputDispatchMouseEvent("mouseReleased", centre[0], centre[1]));
        return "Clicked the element matching '" + selector + "'.";
    }

    /** Focus the first element matching {@code selector} and type {@code text} one character at a time. */
    public String type(String selector, String text) {
        long nodeId = resolveNode(selector);
        cdp.send(cdp.protocol().domFocus(nodeId));
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            cdp.send(cdp.protocol().inputDispatchKeyEvent("char", new String(Character.toChars(cp))));
            i += Character.charCount(cp);
        }
        return "Typed " + text.length() + " character(s) into the element matching '" + selector + "'.";
    }

    /** Wait until {@code document.readyState == complete} (or a load event fired); returns the readyState. */
    public String waitForLoad(int maxPolls, long pollIntervalMillis) {
        for (int i = 0; i < maxPolls; i++) {
            if (cdp.loadEventSeen()) {
                return "complete";
            }
            JsonNode result = cdp.send(cdp.protocol().runtimeEvaluate("document.readyState"));
            String state = evaluateString(result, "");
            if ("complete".equals(state)) {
                return state;
            }
            sleep(pollIntervalMillis);
        }
        // Final read after the last interval.
        return evaluateString(cdp.send(cdp.protocol().runtimeEvaluate("document.readyState")), "loading");
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Resolve {@code selector} to a DOM node id ({@code getDocument} → {@code querySelector}). */
    private long resolveNode(String selector) {
        JsonNode doc = cdp.send(cdp.protocol().domGetDocument());
        JsonNode root = doc == null ? null : doc.path("root").get("nodeId");
        if (root == null || !root.isNumber()) {
            throw new CdpException("Could not read the document root node from Chrome.");
        }
        JsonNode q = cdp.send(cdp.protocol().domQuerySelector(root.asLong(), selector));
        JsonNode nodeId = q == null ? null : q.get("nodeId");
        if (nodeId == null || nodeId.asLong() == 0) {
            throw new CdpException("No element matched the selector '" + selector + "'.");
        }
        return nodeId.asLong();
    }

    /** The centre point of {@code nodeId}'s content box (CDP returns the box as a flat 8-number quad). */
    private double[] boxCentre(long nodeId, String selector) {
        JsonNode box = cdp.send(cdp.protocol().domGetBoxModel(nodeId));
        JsonNode content = box == null ? null : box.path("model").get("content");
        if (content == null || !content.isArray() || content.size() < 8) {
            throw new CdpException("The element matching '" + selector
                    + "' has no layout box (it may be hidden).");
        }
        // content = [x1,y1, x2,y2, x3,y3, x4,y4]; the centre is the average of the four corners.
        double x = (content.get(0).asDouble() + content.get(2).asDouble()
                + content.get(4).asDouble() + content.get(6).asDouble()) / 4.0;
        double y = (content.get(1).asDouble() + content.get(3).asDouble()
                + content.get(5).asDouble() + content.get(7).asDouble()) / 4.0;
        return new double[] {x, y};
    }

    /** The {@code result.result.value} of a {@code Runtime.evaluate} response, or {@code null}. */
    private static JsonNode resultValue(JsonNode evaluateResult) {
        if (evaluateResult == null) {
            return null;
        }
        JsonNode exception = evaluateResult.get("exceptionDetails");
        if (exception != null && !exception.isNull()) {
            JsonNode text = exception.get("text");
            throw new CdpException("The page script threw: "
                    + (text == null ? exception.toString() : text.asText()) + ".");
        }
        JsonNode inner = evaluateResult.get("result");
        return inner == null ? null : inner.get("value");
    }

    /** A {@code Runtime.evaluate} string result, or {@code fallback} when null/absent. */
    private static String evaluateString(JsonNode evaluateResult, String fallback) {
        JsonNode value = resultValue(evaluateResult);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    /** A JS string literal for safe interpolation into an evaluated expression. */
    private static String jsString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
