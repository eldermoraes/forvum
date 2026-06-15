package ai.forvum.channel.whatsapp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The Meta {@code X-Hub-Signature-256} validation (the channel's primary security gate): a POST is
 * processed only if the header is a valid HMAC-SHA256 of the raw body keyed by the app secret. The
 * expected signature is computed here INDEPENDENTLY (a second Mac instance) so the test is a real oracle,
 * not the production method checked against itself. A plain unit test — pure crypto, no socket.
 */
class WhatsAppSignatureTest {

    private static final String SECRET = "shhh-this-is-the-app-secret";
    private static final byte[] BODY =
            "{\"object\":\"whatsapp_business_account\",\"entry\":[]}".getBytes(StandardCharsets.UTF_8);

    /** Independent oracle: {@code sha256=} + lower-case hex HMAC-SHA256(secret, body). */
    private static String sign(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(body);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return "sha256=" + hex;
    }

    @Test
    void aGenuineSignatureValidates() throws Exception {
        assertTrue(WhatsAppSignature.isValid(BODY, SECRET, sign(BODY, SECRET)));
    }

    @Test
    void aTamperedBodyIsRejected() throws Exception {
        String goodSig = sign(BODY, SECRET);
        byte[] tampered = "{\"object\":\"whatsapp_business_account\",\"entry\":[1]}"
                .getBytes(StandardCharsets.UTF_8);
        assertFalse(WhatsAppSignature.isValid(tampered, SECRET, goodSig),
                "a body that does not match the signature must be rejected");
    }

    @Test
    void aWrongSecretIsRejected() throws Exception {
        assertFalse(WhatsAppSignature.isValid(BODY, "not-the-secret", sign(BODY, SECRET)));
    }

    @Test
    void aHeaderWithoutTheSha256PrefixIsRejected() throws Exception {
        String hexOnly = sign(BODY, SECRET).substring("sha256=".length());
        assertFalse(WhatsAppSignature.isValid(BODY, SECRET, hexOnly), "the sha256= prefix is required");
        assertFalse(WhatsAppSignature.isValid(BODY, SECRET, "sha1=" + hexOnly));
    }

    @Test
    void aNullOrBlankInputIsRejected() {
        assertFalse(WhatsAppSignature.isValid(BODY, SECRET, null), "absent header");
        assertFalse(WhatsAppSignature.isValid(BODY, null, "sha256=abcd"), "null secret");
        assertFalse(WhatsAppSignature.isValid(BODY, "   ", "sha256=abcd"), "blank secret");
        assertFalse(WhatsAppSignature.isValid(null, SECRET, "sha256=abcd"), "null body");
    }

    @Test
    void aDifferentLengthHexIsRejectedSafely() throws Exception {
        // MessageDigest.isEqual is length-safe; a truncated hex must not pass.
        String truncated = sign(BODY, SECRET).substring(0, 20);
        assertFalse(WhatsAppSignature.isValid(BODY, SECRET, truncated));
    }
}
