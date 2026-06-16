package ai.forvum.engine.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.PermissionScope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.Set;

/**
 * {@link DeviceSpecReader}: binds {@code devices/<id>.json} to a {@link Device}. {@code identityId} is
 * required; {@code token} (absent ⇒ empty) and {@code revoked} (absent ⇒ false) are optional; a missing
 * {@code identityId} is a contextual error. Plain unit test (a POJO over {@link JsonNode}, no Quarkus).
 */
class DeviceSpecReaderTest {

    private final DeviceSpecReader reader = new DeviceSpecReader();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void bindsAllFields() throws Exception {
        Device device = reader.parse("phone",
                json("{\"token\":\"secret-1\",\"identityId\":\"alice\",\"revoked\":true}"));
        assertEquals("phone", device.id());
        assertEquals("secret-1", device.token());
        assertEquals("alice", device.identityId());
        assertTrue(device.revoked());
    }

    @Test
    void tokenAndRevokedDefaultWhenAbsent() throws Exception {
        Device device = reader.parse("minimal", json("{\"identityId\":\"bob\"}"));
        assertEquals("", device.token(), "absent token defaults to empty");
        assertEquals("bob", device.identityId());
        assertFalse(device.revoked(), "absent revoked defaults to false");
    }

    @Test
    void rejectsAMissingIdentityId() throws Exception {
        assertThrows(IllegalStateException.class, () -> reader.parse("broken", json("{\"token\":\"x\"}")));
    }

    @Test
    void rejectsABlankIdentityId() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> reader.parse("broken", json("{\"identityId\":\"  \"}")));
    }

    @Test
    void parsesRequestedAndApprovedScopesAndReason() throws Exception {
        Device device = reader.parse("phone", json(
                "{\"identityId\":\"alice\",\"requestedScopes\":[\"FS_READ\",\"FS_WRITE\"],"
              + "\"approvedScopes\":[\"FS_READ\"],\"decisionReason\":\"trusted\"}"));
        assertEquals(Set.of(PermissionScope.FS_READ, PermissionScope.FS_WRITE), device.requestedScopes());
        assertEquals(Set.of(PermissionScope.FS_READ), device.approvedScopes());
        assertEquals("trusted", device.decisionReason());
        assertTrue(device.hasScopeDrift(), "FS_WRITE requested but not approved is drift");
    }

    @Test
    void scopesDefaultToEmptyAndReasonToNullWhenAbsent() throws Exception {
        Device device = reader.parse("minimal", json("{\"identityId\":\"bob\"}"));
        assertTrue(device.requestedScopes().isEmpty());
        assertTrue(device.approvedScopes().isEmpty());
        assertEquals(null, device.decisionReason());
        assertFalse(device.hasScopeDrift(), "no requested scopes never drifts");
    }

    @Test
    void rejectsAnUnknownScopeName() throws Exception {
        assertThrows(IllegalStateException.class, () -> reader.parse("phone",
                json("{\"identityId\":\"alice\",\"requestedScopes\":[\"FS_TELEPORT\"]}")));
    }

    @Test
    void rejectsANonArrayScopesField() throws Exception {
        assertThrows(IllegalStateException.class, () -> reader.parse("phone",
                json("{\"identityId\":\"alice\",\"requestedScopes\":\"FS_READ\"}")));
    }
}
