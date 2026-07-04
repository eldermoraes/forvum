package ai.forvum.tools.web;

/**
 * A runtime failure of a {@code web.search} backend (#192): a non-200 status, a bot-detection challenge
 * page, or unparseable markup (a keyless HTML backend can be blocked or drift). Mirrors
 * {@link EgressDeniedException}: an unchecked exception carrying an actionable, English, secret-free
 * message. The engine's {@code ToolExecutor} audits the thrown call {@code error} and renders the message
 * back to the model as the tool result ([P2-2], [M18] — every tool call gets a result message), so the
 * turn completes rather than aborting. Distinct from {@link EgressDeniedException} (an egress-policy
 * denial), which stays the SSRF-refusal signal.
 */
public class WebSearchException extends RuntimeException {

    public WebSearchException(String message) {
        super(message);
    }
}
