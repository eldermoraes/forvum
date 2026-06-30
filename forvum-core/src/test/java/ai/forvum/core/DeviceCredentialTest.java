package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link DeviceCredential} (#166): the transport-neutral device credential a channel adapter carries into
 * the inbound turn. The security-critical property is that {@link DeviceCredential#toString()} never leaks
 * the token (no secret in logs/errors/ledger), plus the {@link DeviceCredential#ABSENT} sentinel for the
 * paired-by-existence / operator / local path.
 */
class DeviceCredentialTest {

    @Test
    void absentSentinelHasNoDeviceAndIsAbsent() {
        assertTrue(DeviceCredential.ABSENT.isAbsent());
        assertFalse(DeviceCredential.ABSENT.present());
        assertEquals("", DeviceCredential.ABSENT.deviceId());
        assertEquals("", DeviceCredential.ABSENT.token());
    }

    @Test
    void aClaimedDeviceIsPresent() {
        DeviceCredential c = new DeviceCredential("web", "s3cret");
        assertTrue(c.present());
        assertFalse(c.isAbsent());
        assertEquals("web", c.deviceId());
        assertEquals("s3cret", c.token());
    }

    @Test
    void toStringNeverLeaksTheToken() {
        DeviceCredential c = new DeviceCredential("web", "super-secret-token-value");
        String rendered = c.toString();
        assertFalse(rendered.contains("super-secret-token-value"),
                "toString must redact the token so a logged/error-echoed credential never leaks the secret");
        assertTrue(rendered.contains("web"), "the non-secret device id is fine to render");
        assertTrue(rendered.contains("redacted"), "a present token renders as a redaction marker");
    }

    @Test
    void aBlankTokenRendersAsAbsentNotRedacted() {
        assertTrue(new DeviceCredential("web", "").toString().contains("<absent>"),
                "no token to hide — render <absent>, not a misleading <redacted>");
    }

    @Test
    void nullFieldsBecomeEmptyNotNull() {
        DeviceCredential c = new DeviceCredential(null, null);
        assertEquals("", c.deviceId());
        assertEquals("", c.token());
        assertTrue(c.isAbsent());
    }

    @Test
    void deviceIdIsStrippedSoTheChannelBindIsExact() {
        // The engine binds deviceId == channelId; a stray-whitespace device id must not defeat that compare.
        assertEquals("web", new DeviceCredential("  web  ", "t").deviceId());
    }

    @Test
    void tokenIsPreservedVerbatimForAByteExactTimingSafeCompare() {
        // The token is the shared secret; it must NOT be stripped/altered, or the constant-time compare fails.
        assertEquals("  spaced-token  ", new DeviceCredential("web", "  spaced-token  ").token());
    }
}
