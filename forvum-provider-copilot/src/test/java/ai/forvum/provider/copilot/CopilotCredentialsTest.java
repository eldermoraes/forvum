package ai.forvum.provider.copilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.provider.copilot.CopilotAuth.CopilotToken;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link CopilotCredentials}: storing/reading the long-lived GitHub token owner-only, the not-logged-in
 * failure, and the Copilot-token exchange-and-cache (the exchange happens at most once per token lifetime,
 * not per call). Uses a @TempDir home + a fake {@link CopilotHttp} so no live GitHub is touched.
 */
class CopilotCredentialsTest {

    /** A fake returning a fixed Copilot-token JSON from the exchange GET, counting the calls. */
    private static final class CountingHttp implements CopilotHttp {
        final AtomicInteger getCalls = new AtomicInteger();
        private final long expiresAtSeconds;

        CountingHttp(long expiresAtSeconds) {
            this.expiresAtSeconds = expiresAtSeconds;
        }

        @Override
        public Resp postForm(String url, Map<String, String> form, Map<String, String> headers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Resp get(String url, Map<String, String> headers) {
            getCalls.incrementAndGet();
            return new Resp(200, "{\"token\":\"tid=abc;proxy-ep=proxy.x.githubcopilot.com\","
                    + "\"expires_at\":" + expiresAtSeconds + "}");
        }
    }

    private static CopilotCredentials creds(Path home, CopilotHttp http) {
        return new CopilotCredentials(home, new CopilotAuth(http));
    }

    @Test
    void storesTheGitHubTokenOwnerOnlyAndReadsItBack(@TempDir Path home) throws Exception {
        CopilotCredentials creds = creds(home, new CountingHttp(0));
        assertFalse(creds.isAuthenticated(), "not authenticated before login");

        creds.storeGitHubToken("gho_SECRET");

        assertTrue(creds.isAuthenticated());
        assertEquals("gho_SECRET", creds.readGitHubToken().orElseThrow());
        Path file = home.resolve("state").resolve("credentials").resolve("github-copilot.json");
        assertTrue(Files.isRegularFile(file));
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
                    "the GitHub token file must be owner-only (0600)");
        }
    }

    @Test
    void currentApiTokenThrowsWhenNotLoggedIn(@TempDir Path home) {
        assertThrows(CopilotAuthException.class, () -> creds(home, new CountingHttp(0)).currentApiToken());
    }

    @Test
    void currentApiTokenExchangesOnceAndCaches(@TempDir Path home) {
        long farFuture = System.currentTimeMillis() / 1000 + 3600; // +1h, well past the 5-min margin
        CountingHttp http = new CountingHttp(farFuture);
        CopilotCredentials creds = creds(home, http);
        creds.storeGitHubToken("gho_SECRET");

        CopilotToken first = creds.currentApiToken();
        CopilotToken second = creds.currentApiToken();

        assertEquals("https://api.x.githubcopilot.com", first.baseUrl());
        assertEquals(first.token(), second.token());
        assertEquals(1, http.getCalls.get(), "the Copilot token is cached, not re-exchanged per call");
    }

    @Test
    void anExpiredCachedTokenIsReExchanged(@TempDir Path home) {
        // expires_at already in the past → the 5-min-margin check is false on every call → re-exchange.
        long past = System.currentTimeMillis() / 1000 - 10;
        CountingHttp http = new CountingHttp(past);
        CopilotCredentials creds = creds(home, http);
        creds.storeGitHubToken("gho_SECRET");

        creds.currentApiToken();
        creds.currentApiToken();

        assertEquals(2, http.getCalls.get(), "a near/expired Copilot token is re-exchanged, not served stale");
    }

    @Test
    void aBlankOrMissingOrMalformedTokenFileReadsAsNotAuthenticated(@TempDir Path home) throws Exception {
        CopilotCredentials creds = creds(home, new CountingHttp(0));
        assertFalse(creds.isAuthenticated(), "absent file → not authenticated");

        Path file = home.resolve("state").resolve("credentials").resolve("github-copilot.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"token\":\"  \"}"); // blank token
        assertTrue(creds.readGitHubToken().isEmpty(), "a blank token reads as absent");

        Files.writeString(file, "{ not json"); // malformed
        assertTrue(creds.readGitHubToken().isEmpty(), "a malformed credentials file reads as absent, not fatal");
    }

    @Test
    void storingAFreshGitHubTokenInvalidatesTheCachedCopilotToken(@TempDir Path home) {
        long farFuture = System.currentTimeMillis() / 1000 + 3600;
        CountingHttp http = new CountingHttp(farFuture);
        CopilotCredentials creds = creds(home, http);
        creds.storeGitHubToken("gho_FIRST");
        creds.currentApiToken();                 // exchange #1

        creds.storeGitHubToken("gho_SECOND");    // re-login must drop the cache
        creds.currentApiToken();                 // exchange #2

        assertEquals(2, http.getCalls.get(), "a re-login re-exchanges the Copilot token");
    }
}
