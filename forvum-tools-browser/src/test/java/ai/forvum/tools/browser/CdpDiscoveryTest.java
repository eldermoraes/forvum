package ai.forvum.tools.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unit tests for the pure {@link CdpDiscovery} against a scripted {@link FakeCdpHttp} (no live Chrome):
 * first-page-target selection, the browser-level fallback, and the unreachable / no-target failures.
 */
class CdpDiscoveryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A scripted {@link CdpHttp} mapping URL → response (or a thrown {@link CdpException}). */
    static final class FakeCdpHttp implements CdpHttp {
        private final Map<String, Resp> responses = new LinkedHashMap<>();
        private boolean unreachable;

        FakeCdpHttp on(String url, int status, String body) {
            responses.put(url, new Resp(status, body));
            return this;
        }

        FakeCdpHttp unreachable() {
            this.unreachable = true;
            return this;
        }

        @Override
        public Resp get(String url) {
            if (unreachable) {
                throw new CdpException("Cannot reach Chrome at " + url + ".");
            }
            Resp resp = responses.get(url);
            if (resp == null) {
                throw new AssertionError("Unexpected GET " + url);
            }
            return resp;
        }
    }

    private CdpDiscovery discovery(CdpHttp http) {
        return new CdpDiscovery(http, MAPPER);
    }

    @Test
    void picksTheFirstOpenPageTargetWebSocketUrl() {
        FakeCdpHttp http = new FakeCdpHttp().on("http://localhost:9222/json", 200, """
                [
                  {"type":"background_page","webSocketDebuggerUrl":"ws://localhost:9222/devtools/page/bg"},
                  {"type":"page","webSocketDebuggerUrl":"ws://localhost:9222/devtools/page/AAA"},
                  {"type":"page","webSocketDebuggerUrl":"ws://localhost:9222/devtools/page/BBB"}
                ]""");

        assertEquals("ws://localhost:9222/devtools/page/AAA",
                discovery(http).pageWebSocketUrl("http://localhost:9222"),
                "the first type==page target is driven (attach-to-first-page, v0.1 default)");
    }

    @Test
    void toleratesATrailingSlashOnTheDebugUrl() {
        FakeCdpHttp http = new FakeCdpHttp().on("http://localhost:9222/json", 200,
                "[{\"type\":\"page\",\"webSocketDebuggerUrl\":\"ws://localhost:9222/devtools/page/X\"}]");

        assertEquals("ws://localhost:9222/devtools/page/X",
                discovery(http).pageWebSocketUrl("http://localhost:9222/"));
    }

    @Test
    void fallsBackToTheBrowserLevelEndpointWhenNoPageIsOpen() {
        FakeCdpHttp http = new FakeCdpHttp()
                .on("http://localhost:9222/json", 200, "[]")
                .on("http://localhost:9222/json/version", 200,
                        "{\"Browser\":\"Chrome/120\",\"webSocketDebuggerUrl\":\"ws://localhost:9222/devtools/browser/Z\"}");

        assertEquals("ws://localhost:9222/devtools/browser/Z",
                discovery(http).pageWebSocketUrl("http://localhost:9222"),
                "no page target → the browser-level WS endpoint is the fallback");
    }

    @Test
    void throwsWhenChromeIsUnreachable() {
        CdpException e = assertThrows(CdpException.class,
                () -> discovery(new FakeCdpHttp().unreachable()).pageWebSocketUrl("http://localhost:9222"));
        assertTrue(e.getMessage().contains("9222"));
    }

    @Test
    void throwsWhenNoDebuggableTargetExists() {
        FakeCdpHttp http = new FakeCdpHttp()
                .on("http://localhost:9222/json", 200, "[]")
                .on("http://localhost:9222/json/version", 200, "{\"Browser\":\"Chrome/120\"}");

        CdpException e = assertThrows(CdpException.class,
                () -> discovery(http).pageWebSocketUrl("http://localhost:9222"));
        assertTrue(e.getMessage().contains("no debuggable page target"));
    }

    @Test
    void throwsOnANon200DiscoveryResponse() {
        FakeCdpHttp http = new FakeCdpHttp().on("http://localhost:9222/json", 500, "boom");
        assertThrows(CdpException.class,
                () -> discovery(http).pageWebSocketUrl("http://localhost:9222"));
    }
}
