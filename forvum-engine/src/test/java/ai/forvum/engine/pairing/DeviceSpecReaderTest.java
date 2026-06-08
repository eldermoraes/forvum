package ai.forvum.engine.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

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
}
