package ai.forvum.tools.browser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * LIVE end-to-end test (default-off, {@code @Tag("live")} — opt in with
 * {@code -DexcludedGroups= -Dgroups=live}). It launches a real headless Chrome on a debug port, drives the
 * full {@link BrowserToolProvider} → {@link CdpSession} → {@link BasicWebSocketConnector} path (the
 * production transport this refactor introduced), and asserts {@code browser.navigate} / {@code snapshot} /
 * {@code extract} against a {@code data:} URL.
 *
 * <p>The {@code forvum.home} is pinned (the {@link Profile}) to a temp dir whose {@code tools/browser.json}
 * enables the tool and points at the launched Chrome's port — so the lazy CDP connect actually dials. If
 * Chrome cannot be launched on this host (no binary, sandboxed CI), {@link #chromeLaunched} stays false and
 * every test {@code assumeTrue}-skips: the test then documents the path without failing the build, and the
 * NON-live {@link CdpProtocolTest}/{@link CdpFrameRouterTest}/{@link BrowserOperationsTest} +
 * {@link BrowserToolProviderWiringIT} remain the CI gate.
 *
 * <p>Run from the module: {@code ./mvnw -pl forvum-tools-browser test -DexcludedGroups= -Dgroups=live
 * -Dforvum.live.home=$(mktemp -d)} (the home dir is seeded in {@link #launchChrome}; see {@link Profile}).
 */
@QuarkusTest
@TestProfile(BrowserLiveIT.Profile.class)
@Tag("live")
class BrowserLiveIT {

    /** A fixed temp home + debug port shared across the launch hook and the Quarkus config profile. */
    static final Path LIVE_HOME = Path.of(System.getProperty("java.io.tmpdir"), "forvum-browser-live-home");
    static final int DEBUG_PORT = 9333;

    static Process chrome;
    static boolean chromeLaunched;

    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", LIVE_HOME.toString());
        }
    }

    @Inject
    BrowserToolProvider provider;

    @BeforeAll
    static void launchChrome() throws IOException, InterruptedException {
        Path chromeBinary = Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        if (!Files.isExecutable(chromeBinary)) {
            chromeLaunched = false;
            return;
        }
        Path userData = Files.createTempDirectory("forvum-browser-live-profile");
        Path tools = Files.createDirectories(LIVE_HOME.resolve("tools"));
        Files.writeString(tools.resolve("browser.json"),
                "{\"enabled\":true,\"debugUrl\":\"http://localhost:" + DEBUG_PORT + "\","
              + "\"connectTimeoutMs\":8000,\"navigateTimeoutMs\":20000}");

        // NOTE: deliberately launched WITHOUT --remote-allow-origins, to prove the tool dials a plain
        // `chrome --remote-debugging-port=<n>` (the production CdpSession.dial suppresses the Origin header
        // Chrome would otherwise 500 on).
        chrome = new ProcessBuilder(
                chromeBinary.toString(),
                "--headless=new",
                "--remote-debugging-port=" + DEBUG_PORT,
                "--user-data-dir=" + userData,
                "--no-first-run",
                "--no-default-browser-check",
                "about:blank")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();

        chromeLaunched = waitForDebugEndpoint();
    }

    @AfterAll
    static void killChrome() {
        if (chrome != null) {
            chrome.destroy();
            try {
                if (!chrome.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    chrome.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                chrome.destroyForcibly();
            }
        }
    }

    @Test
    void navigateThenSnapshotReturnsThePageText() {
        assumeTrue(chromeLaunched, "headless Chrome could not be launched on this host — live test skipped");

        String dataUrl = "data:text/html,<html><body><h1 id=t>Forvum%20live</h1>"
                + "<p class=c>hello%20cdp</p></body></html>";
        String navigated = provider.invoke("browser.navigate", Map.of("url", dataUrl));
        assertFalse(navigated.startsWith("Browser tool error:"), navigated);

        provider.invoke("browser.wait", Map.of());
        String text = provider.invoke("browser.snapshot", Map.of());
        assertFalse(text.startsWith("Browser tool error:"), text);
        assertTrue(text.contains("Forvum live"), "the page body text is read back over CDP: " + text);
    }

    @Test
    void extractReadsASelectorTextContent() {
        assumeTrue(chromeLaunched, "headless Chrome could not be launched on this host — live test skipped");

        String dataUrl = "data:text/html,<html><body><span class=tag>extracted!</span></body></html>";
        provider.invoke("browser.navigate", Map.of("url", dataUrl));
        provider.invoke("browser.wait", Map.of());

        String extracted = provider.invoke("browser.extract", Map.of("selector", ".tag"));
        assertTrue(extracted.contains("extracted!"), "extract returns the selector's textContent: " + extracted);
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Poll Chrome's {@code /json/version} until it answers (the remote-debugging server is up), or give up. */
    private static boolean waitForDebugEndpoint() throws InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + DEBUG_PORT + "/json/version"))
                .timeout(Duration.ofSeconds(1)).GET().build();
        for (int i = 0; i < 40; i++) { // up to ~10 s
            try {
                HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    return true;
                }
            } catch (IOException ignored) {
                // server not up yet
            }
            Thread.sleep(250);
        }
        return false;
    }
}
