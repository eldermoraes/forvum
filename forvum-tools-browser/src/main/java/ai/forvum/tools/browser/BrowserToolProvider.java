package ai.forvum.tools.browser;

import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.AbstractToolProvider;
import ai.forvum.sdk.ForvumExtension;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * The browser-automation tool plugin (P2-1, #26). Contributes the browser tools — read-only
 * {@code browser.navigate}/{@code snapshot}/{@code extract}/{@code wait} and the
 * {@code userConfirmRequired} mutators {@code browser.click}/{@code type} — to the engine's global
 * ToolRegistry (discovered via CDI as an {@code @ApplicationScoped} bean) and (M18 Option A) executes them
 * through {@link #invoke(String, Map)} by dispatching on the tool name (no reflection). The engine's
 * ToolExecutor gates permission ({@link ai.forvum.core.PermissionScope#WEB_BROWSE}), parks every
 * {@code userConfirmRequired} call through the #39 approval gate, and audits the outcome; this provider only
 * drives the permitted call against the operator-attached Chrome via the {@link CdpSession}.
 *
 * <p>The CDP WebSocket is opened LAZILY on the first invoke (no {@code @Startup} network work). When Chrome
 * is not attached (or the tool is disabled in {@code tools/browser.json}), {@link #invoke} returns a clear
 * model-facing error string rather than throwing — the engine audits the call {@code error} and the model
 * relays the message, never crashing the turn or boot (the [M14] graceful-absence contract).
 */
@ForvumExtension
@ApplicationScoped
public class BrowserToolProvider extends AbstractToolProvider {

    /** Bounded polling for {@code browser.wait}: 20 polls × 250 ms ≈ 5 s, independent of the CDP timeout. */
    static final int WAIT_MAX_POLLS = 20;
    static final long WAIT_POLL_INTERVAL_MS = 250;

    /**
     * The CDP execution seam (the {@code @ApplicationScoped} {@link CdpSession} in production). Injected as
     * the {@link CdpExecutor} interface so a unit test can substitute a scripted fake and drive every
     * dispatch branch with no live Chrome.
     */
    @Inject
    CdpExecutor session;

    @Override
    public String extensionId() {
        return "browser";
    }

    @Override
    public List<ToolSpec> tools() {
        return BrowserTools.ALL;
    }

    @Override
    public String invoke(String toolName, Map<String, Object> arguments) {
        BrowserOperations ops = new BrowserOperations(session);
        try {
            return switch (toolName) {
                case "browser.navigate" -> ops.navigate(stringArg(arguments, "url"));
                case "browser.snapshot" -> ops.snapshot();
                case "browser.extract" -> ops.extract(stringArg(arguments, "selector"));
                case "browser.wait" -> ops.waitForLoad(WAIT_MAX_POLLS, WAIT_POLL_INTERVAL_MS);
                case "browser.click" -> ops.click(stringArg(arguments, "selector"));
                case "browser.type" -> ops.type(stringArg(arguments, "selector"),
                        stringArg(arguments, "text"));
                default -> throw new IllegalArgumentException(
                        "BrowserToolProvider does not contribute a tool named '" + toolName
                      + "'. It provides browser.navigate, browser.snapshot, browser.extract, browser.wait, "
                      + "browser.click, browser.type.");
            };
        } catch (CdpException e) {
            // A browser failure (unreachable Chrome, CDP error, missing element, timeout) is relayed to the
            // model as a clear string — the engine audits the call 'error'; the turn is not crashed.
            return "Browser tool error: " + e.getMessage();
        }
    }

    /** The required {@code String} argument {@code key}; the model is contractually obliged to supply it. */
    private static String stringArg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Missing required argument '" + key + "' for a browser tool call.");
        }
        return value.toString();
    }
}
