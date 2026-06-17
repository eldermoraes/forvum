package ai.forvum.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@link JdkHttpFetcher} HTTP IP-pin (B2): it connects to the guard-validated literal IP while
 * presenting the original virtual host in a {@code Host} header, and preserves the raw (already-encoded)
 * path/query verbatim. Without coverage here both the restricted-header set (D1) and the raw-component
 * preservation (D2) shipped green — this pins both.
 */
class JdkHttpFetcherTest {

    static {
        // Deterministically allow the restricted Host header before any java.net.http client is built in
        // this test JVM (the production set lives in ForvumApplication.main; this mirrors it for the unit).
        if (System.getProperty("jdk.httpclient.allowRestrictedHeaders") == null) {
            System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
        }
    }

    @Test
    void rewriteToIpPreservesRawPathAndQueryVerbatim() throws Exception {
        // Percent-encoded reserved chars (%2F = '/', %20 = ' ', %26 = '&', %3D = '=') must survive the
        // authority rewrite unchanged; the decoded getPath()/getQuery() fed to the multi-arg URI ctor would
        // under-escape them and change which resource is fetched.
        URI original = URI.create("http://example.test:8080/a%2Fb%20c/d?k=a%26b%3Dc");
        URI rewritten = JdkHttpFetcher.rewriteToIp(original, InetAddress.getByName("127.0.0.1"));

        assertEquals("127.0.0.1", rewritten.getHost());
        assertEquals(8080, rewritten.getPort());
        assertEquals("/a%2Fb%20c/d", rewritten.getRawPath());
        assertEquals("k=a%26b%3Dc", rewritten.getRawQuery());
    }

    @Test
    void rewriteToIpBracketsAnIpv6Pin() throws Exception {
        URI rewritten = JdkHttpFetcher.rewriteToIp(
                URI.create("http://example.test:8080/x"), InetAddress.getByName("::1"));

        assertTrue(rewritten.toString().contains("[0:0:0:0:0:0:0:1]:8080"),
                "an IPv6 pin must be bracketed in the authority: " + rewritten);
        assertEquals(8080, rewritten.getPort());
        assertEquals("/x", rewritten.getRawPath());
    }

    @Test
    void hostHeaderCarriesTheOriginalHostAndNonDefaultPort() {
        assertEquals("example.test:8080", JdkHttpFetcher.hostHeader(URI.create("http://example.test:8080/x")));
        assertEquals("example.test", JdkHttpFetcher.hostHeader(URI.create("http://example.test/x")));
    }

    @Test
    void httpFetchConnectsToThePinnedIpAndSendsTheOriginalHostHeader() throws Exception {
        AtomicReference<String> seenHost = new AtomicReference<>();
        AtomicReference<String> seenRawPath = new AtomicReference<>();
        AtomicReference<String> seenRawQuery = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            seenHost.set(exchange.getRequestHeaders().getFirst("Host"));
            seenRawPath.set(exchange.getRequestURI().getRawPath());
            seenRawQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            // Host the request names a virtual host that does NOT resolve; the pin (127.0.0.1) is where we
            // actually connect — proving the IP-pin + Host-header path works (and that the restricted Host
            // header is accepted, the D1 regression). The raw %2F / %26 must reach the server intact (D2).
            URI uri = URI.create("http://forvum.virtual.example:" + port + "/a%2Fb/c?k=x%26y");
            EgressGuard.Approved approved = new EgressGuard.Approved(uri, InetAddress.getByName("127.0.0.1"));

            HttpFetcher.FetchResult result = new JdkHttpFetcher().get(approved);

            assertEquals(200, result.status());
            assertTrue(result.body().contains("ok"), "the pinned fetch returned the server body");
            assertEquals("forvum.virtual.example:" + port, seenHost.get(),
                    "the server must see the ORIGINAL virtual host, not the pinned IP");
            assertEquals("/a%2Fb/c", seenRawPath.get(), "the raw (encoded) path must be preserved verbatim");
            assertEquals("k=x%26y", seenRawQuery.get(), "the raw (encoded) query must be preserved verbatim");
        } finally {
            server.stop(0);
        }
    }
}
