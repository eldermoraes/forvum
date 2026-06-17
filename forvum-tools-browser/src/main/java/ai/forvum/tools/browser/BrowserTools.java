package ai.forvum.tools.browser;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;

import java.util.List;

/**
 * The browser tools (P2-1, #26) the {@link BrowserToolProvider} contributes, all under
 * {@link PermissionScope#WEB_BROWSE}. The READ-only tools ({@link #NAVIGATE}, {@link #SNAPSHOT},
 * {@link #EXTRACT}, {@link #WAIT}) use the 4-arg {@link ToolSpec} constructor
 * ({@code userConfirmRequired=false}); the MUTATING tools ({@link #CLICK}, {@link #TYPE}) use the 5-arg
 * constructor with {@code userConfirmRequired=true}, opting them in to the P2-14 #39 approval gate
 * automatically — the engine's {@code ToolExecutor} parks every {@code browser.click}/{@code browser.type}
 * call through the SQLite-backed approval queue (this module ships ZERO approval code; it only declares the
 * flag). {@code browser.navigate} stays {@code false} for usability (the maintainer default; the
 * confirm-gate granularity rules navigate read-exfiltration out of scope for v0.1).
 */
public final class BrowserTools {

    private BrowserTools() {
    }

    public static final ToolSpec NAVIGATE = new ToolSpec(
            "browser.navigate",
            "Navigate the attached browser to a URL and wait for the page to load. Returns the final URL.",
            PermissionScope.WEB_BROWSE,
            "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\","
          + "\"description\":\"the absolute URL to navigate to\"}},\"required\":[\"url\"]}");

    public static final ToolSpec SNAPSHOT = new ToolSpec(
            "browser.snapshot",
            "Return the visible text content of the current page (document.body.innerText).",
            PermissionScope.WEB_BROWSE,
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}");

    public static final ToolSpec EXTRACT = new ToolSpec(
            "browser.extract",
            "Return the text content of the first element matching a CSS selector on the current page.",
            PermissionScope.WEB_BROWSE,
            "{\"type\":\"object\",\"properties\":{\"selector\":{\"type\":\"string\","
          + "\"description\":\"a CSS selector\"}},\"required\":[\"selector\"]}");

    public static final ToolSpec WAIT = new ToolSpec(
            "browser.wait",
            "Wait until the current page finishes loading (document.readyState == complete), up to the "
          + "configured timeout. Returns the readyState.",
            PermissionScope.WEB_BROWSE,
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}");

    public static final ToolSpec CLICK = new ToolSpec(
            "browser.click",
            "Click the first element matching a CSS selector on the current page. Requires user approval.",
            PermissionScope.WEB_BROWSE,
            "{\"type\":\"object\",\"properties\":{\"selector\":{\"type\":\"string\","
          + "\"description\":\"a CSS selector for the element to click\"}},\"required\":[\"selector\"]}",
            true);

    public static final ToolSpec TYPE = new ToolSpec(
            "browser.type",
            "Type text into the first element matching a CSS selector on the current page. Requires user "
          + "approval.",
            PermissionScope.WEB_BROWSE,
            "{\"type\":\"object\",\"properties\":{"
          + "\"selector\":{\"type\":\"string\",\"description\":\"a CSS selector for the input element\"},"
          + "\"text\":{\"type\":\"string\",\"description\":\"the text to type\"}},"
          + "\"required\":[\"selector\",\"text\"]}",
            true);

    /** All six tools, in a stable order. */
    public static final List<ToolSpec> ALL = List.of(NAVIGATE, SNAPSHOT, EXTRACT, WAIT, CLICK, TYPE);
}
