package ai.forvum.engine.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The pure secrets-only redactor (P2-OUTPUTGUARD, DR-6a §9.2): well-known credential shapes are masked to
 * their scheme prefix, ordinary prose is left untouched, and the redaction count is exact.
 */
class SecretRedactorTest {

    @Test
    void masksAnOpenAiStyleKeyKeepingOnlyThePrefix() {
        SecretRedactor.Result r = SecretRedactor.redact("here is sk-ant-api03-ABCdef123456ghi789 ok");
        assertTrue(r.content().contains("sk-ant-***"), "prefix is kept: " + r.content());
        assertFalse(r.content().contains("ABCdef123456ghi789"), "the secret body is gone: " + r.content());
        assertEquals(1, r.redactions());
    }

    @Test
    void masksSlackGitHubGoogleAwsTelegramBearerAndPemBlocks() {
        assertRedactedTo("xoxb-1234567890-abcdEFGHijkl", "xoxb-***");
        assertRedactedTo("ghp_0123456789ABCDEFabcdef0123", "ghp_***");
        assertRedactedTo("github_pat_11ABCDEFG0abcdefgh_ij012345", "github_pat_***");
        assertRedactedTo("AIzaSyA0123456789abcdefABCDEF_-xyz", "AIza***");
        assertRedactedTo("AKIAIOSFODNN7EXAMPLE", "AKIA***");
        assertRedactedTo("123456789:AAEhd-abcDEFghIJKlmNOPqrstuvWXYz012", "123456789:***");
        assertRedactedTo("Authorization: Bearer abcDEF1234567890ghiJKL", "Bearer ***");
        SecretRedactor.Result pem = SecretRedactor.redact(
            "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAK\nbase64stuff\n-----END RSA PRIVATE KEY-----");
        assertEquals("[redacted-private-key]", pem.content());
        assertEquals(1, pem.redactions());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "the sky is blue and the API returned a token",
        "ask-me about authentication, it is a normal sentence",
        "I use sk- as a variable name but no secret here",
        "Bearer with no token following it",
        "version sk-123"  // too short a body to be a key
    })
    void leavesOrdinaryProseUntouched(String prose) {
        SecretRedactor.Result r = SecretRedactor.redact(prose);
        assertEquals(prose, r.content(), "no false-positive redaction");
        assertEquals(0, r.redactions());
    }

    @Test
    void countsEverySecretAcrossMultipleMatches() {
        SecretRedactor.Result r = SecretRedactor.redact(
            "k1 sk-ABCdef123456ghi789 and k2 sk-ZYXwvu987654tsr321 done");
        assertEquals(2, r.redactions());
        assertFalse(r.content().contains("ABCdef123456ghi789"));
        assertFalse(r.content().contains("ZYXwvu987654tsr321"));
    }

    @Test
    void handlesNullAndEmptyWithoutRedaction() {
        assertEquals(0, SecretRedactor.redact(null).redactions());
        assertEquals(null, SecretRedactor.redact(null).content());
        assertEquals(0, SecretRedactor.redact("").redactions());
    }

    private static void assertRedactedTo(String secret, String expectedMaskedPrefix) {
        SecretRedactor.Result r = SecretRedactor.redact("value is " + secret + " trailing");
        assertTrue(r.content().contains(expectedMaskedPrefix),
            "expected '" + expectedMaskedPrefix + "' in: " + r.content());
        assertFalse(r.content().contains(secret), "the raw secret must be gone: " + r.content());
        assertEquals(1, r.redactions());
    }
}
