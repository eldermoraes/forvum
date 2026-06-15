package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link McpListCommand#redactUrl} masks secret material before {@code mcp list} prints a server URL: a
 * plain URL is shown as-is (scheme://host[:port]/path), but {@code userinfo} and a non-empty query string
 * (where an operator could embed a token) are dropped/sentinelled, and an unparseable URL is masked
 * wholesale. Plain unit test — no Quarkus boot.
 */
class McpListRedactTest {

    @Test
    void plainUrlIsShownUnchanged() {
        assertEquals("http://mcp.example:9000/sse",
                McpListCommand.redactUrl("http://mcp.example:9000/sse"));
        assertEquals("https://mcp.example/sse", McpListCommand.redactUrl("https://mcp.example/sse"));
    }

    @Test
    void userInfoIsStripped() {
        String redacted = McpListCommand.redactUrl("https://user:s3cret@mcp.example/sse");
        assertFalse(redacted.contains("s3cret"), "the password must not be printed; got: " + redacted);
        assertFalse(redacted.contains("user"), "the userinfo must not be printed; got: " + redacted);
        assertTrue(redacted.startsWith("https://mcp.example/sse"), "host/path kept; got: " + redacted);
        assertTrue(redacted.endsWith("?<redacted>"), "userinfo presence is flagged; got: " + redacted);
    }

    @Test
    void queryStringIsRedacted() {
        String redacted = McpListCommand.redactUrl("https://mcp.example/sse?token=abc123");
        assertFalse(redacted.contains("abc123"), "the token must not be printed; got: " + redacted);
        assertEquals("https://mcp.example/sse?<redacted>", redacted);
    }

    @Test
    void unparseableUrlIsMaskedWholesale() {
        assertEquals("<redacted>", McpListCommand.redactUrl("ht tp://bad url with spaces"));
    }
}
