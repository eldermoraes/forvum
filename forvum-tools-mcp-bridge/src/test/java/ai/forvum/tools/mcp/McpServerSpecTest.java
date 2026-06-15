package ai.forvum.tools.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * {@link McpServerSpec#isHttp()}: the v0.5 transport gate. {@code "http"}/{@code "sse"} (any case) are the
 * supported HTTP/SSE transport; {@code "stdio"} and anything else (incl. {@code null}) is unsupported and
 * skipped by the provider (Risk #9 — no subprocess spawn until the native smoke passes).
 */
class McpServerSpecTest {

    private static McpServerSpec spec(String transport) {
        return new McpServerSpec("s", true, transport, "http://localhost:9000/sse", Map.of());
    }

    @Test
    void httpAndSseTransportsAreSupportedCaseInsensitively() {
        assertTrue(spec("http").isHttp());
        assertTrue(spec("HTTP").isHttp());
        assertTrue(spec("sse").isHttp());
        assertTrue(spec("Sse").isHttp());
    }

    @Test
    void stdioAndUnknownAndNullTransportsAreNotHttp() {
        assertFalse(spec("stdio").isHttp(), "stdio is parsed but unsupported in v0.5");
        assertFalse(spec("grpc").isHttp(), "an unknown transport is unsupported");
        assertFalse(spec(null).isHttp(), "a null transport is unsupported (never throws)");
    }
}
