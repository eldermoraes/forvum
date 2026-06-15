package ai.forvum.channel.whatsapp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Validates Meta's {@code X-Hub-Signature-256} webhook signature (pure, socket-free, so it is unit-tested
 * with no HTTP). Per the Meta webhook security contract, every Event Notification POST is signed as
 * {@code sha256=<hex>} where {@code <hex>} is the lower-case hexadecimal HMAC-SHA256 of the <em>raw</em>
 * request body keyed by the app secret. {@link #isValid} recomputes that HMAC and compares it to the
 * header in CONSTANT TIME ({@link MessageDigest#isEqual}, which is also length-safe), so a tampered body,
 * a wrong/blank secret, or a missing/malformed header all return {@code false} and the POST is rejected
 * before any turn runs. Keeping this off the transport layer is what makes the security check testable
 * without a live webhook.
 */
final class WhatsAppSignature {

    /** Header name Meta stamps the signature on. */
    static final String HEADER = "X-Hub-Signature-256";
    private static final String PREFIX = "sha256=";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private WhatsAppSignature() {
    }

    /**
     * Whether {@code signatureHeader} is a valid {@code X-Hub-Signature-256} for {@code rawBody} under
     * {@code appSecret}. Returns {@code false} (never throws) for any null/blank input or a header that
     * does not carry the {@code sha256=} prefix — an unverifiable request is an invalid one.
     */
    static boolean isValid(byte[] rawBody, String appSecret, String signatureHeader) {
        if (rawBody == null || appSecret == null || appSecret.isBlank()
                || signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        String expectedHex = hmacSha256Hex(appSecret, rawBody);
        if (expectedHex == null) {
            return false;
        }
        String providedHex = signatureHeader.substring(PREFIX.length()).trim();
        // Constant-time, length-safe comparison on the ASCII hex bytes (MessageDigest.isEqual is
        // hardened against timing + length leaks since JDK 6u17).
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.US_ASCII),
                providedHex.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hmacSha256Hex(String secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return toHex(mac.doFinal(message));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is always present; treat any failure as "cannot validate" → reject.
            return null;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
