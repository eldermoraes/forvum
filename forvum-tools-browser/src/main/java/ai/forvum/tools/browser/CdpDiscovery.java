package ai.forvum.tools.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Pure CDP discovery against the operator-launched Chrome's remote-debugging HTTP endpoint, over the
 * {@link CdpHttp} seam so it is testable with a scripted fake (no live Chrome). Two GETs:
 *
 * <ul>
 *   <li>{@code GET <debugUrl>/json} lists the open page targets; v0.1 drives the first {@code type=="page"}
 *       target's {@code webSocketDebuggerUrl} (the maintainer default: attach-to-first-page, documented;
 *       a Forvum-created tab via {@code Target.createTarget} is deferred). When no page target exists this
 *       falls back to the browser-level WS endpoint from {@code /json/version}.</li>
 *   <li>{@code GET <debugUrl>/json/version} yields the browser-level {@code webSocketDebuggerUrl} (used
 *       only when no page target is present).</li>
 * </ul>
 *
 * The discovered {@code ws://...} URL is then dialed by {@link CdpSession} via the websockets-next
 * connector (the discord dynamic-baseUri pattern).
 */
public final class CdpDiscovery {

    private final CdpHttp http;
    private final ObjectMapper mapper;

    public CdpDiscovery(CdpHttp http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    /**
     * The WebSocket debugger URL to attach to: the first open page target's, falling back to the
     * browser-level endpoint when no page is open.
     *
     * @param debugUrl the Chrome remote-debugging HTTP base (e.g. {@code http://localhost:9222})
     * @throws CdpException when Chrome is unreachable or exposes no debuggable target
     */
    public String pageWebSocketUrl(String debugUrl) {
        String base = stripTrailingSlash(debugUrl);
        JsonNode targets = getJson(base + "/json");
        if (targets != null && targets.isArray()) {
            for (JsonNode target : targets) {
                JsonNode type = target.get("type");
                JsonNode wsUrl = target.get("webSocketDebuggerUrl");
                if (type != null && "page".equals(type.asText()) && wsUrl != null && !wsUrl.asText().isBlank()) {
                    return wsUrl.asText();
                }
            }
        }
        // No open page target — fall back to the browser-level endpoint from /json/version.
        JsonNode version = getJson(base + "/json/version");
        JsonNode browserWs = version == null ? null : version.get("webSocketDebuggerUrl");
        if (browserWs != null && !browserWs.asText().isBlank()) {
            return browserWs.asText();
        }
        throw new CdpException(
                "Chrome at " + debugUrl + " exposes no debuggable page target. Open a tab, or start "
              + "Chrome with --remote-debugging-port=9222.");
    }

    private JsonNode getJson(String url) {
        CdpHttp.Resp resp = http.get(url);
        if (resp.status() != 200) {
            throw new CdpException("Chrome's remote-debugging endpoint " + url + " returned HTTP "
                    + resp.status() + ".");
        }
        try {
            return mapper.readTree(resp.body());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse Chrome's remote-debugging response from " + url
                    + ".", e);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
