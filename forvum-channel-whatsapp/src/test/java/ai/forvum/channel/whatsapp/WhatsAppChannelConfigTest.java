package ai.forvum.channel.whatsapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.whatsapp.WhatsAppChannelConfig.Spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.Set;

/**
 * {@link WhatsAppChannelConfig#parse} and {@link Spec#isSenderAllowed}: the file-driven config maps to a
 * Spec, the API version defaults when unset, and the allow-list gate behaves (empty = denies every sender
 * unless {@code allowAllUsers}, #170 fail-closed; a non-empty set restricts). A plain unit test — no
 * Quarkus, no file IO.
 */
class WhatsAppChannelConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Spec parse(String json) {
        try {
            JsonNode root = json == null ? null : MAPPER.readTree(json);
            return WhatsAppChannelConfig.parse(root);
        } catch (Exception e) {
            throw new IllegalArgumentException(json, e);
        }
    }

    @Test
    void aFullConfigParses() {
        Spec spec = parse("""
                { "verifyToken": "vt", "appSecret": "as", "accessToken": "at",
                  "phoneNumberId": "PNID", "apiVersion": "v22.0",
                  "allowedUserIds": ["15550001111", "15557772222"] }
                """);

        assertTrue(spec.enabled());
        assertEquals("vt", spec.verifyToken().orElseThrow());
        assertEquals("as", spec.appSecret().orElseThrow());
        assertEquals("at", spec.accessToken().orElseThrow());
        assertEquals("PNID", spec.phoneNumberId().orElseThrow());
        assertEquals("v22.0", spec.apiVersion());
        assertEquals(Set.of("15550001111", "15557772222"), spec.allowedUserIds());
    }

    @Test
    void theApiVersionDefaultsWhenUnset() {
        assertEquals(WhatsAppChannelConfig.DEFAULT_API_VERSION,
                parse("{ \"verifyToken\": \"vt\" }").apiVersion());
    }

    @Test
    void absentOrNullConfigIsDisabledAndEmpty() {
        Spec empty = parse(null);
        assertFalse(empty.enabled());
        assertTrue(empty.verifyToken().isEmpty());
        assertTrue(empty.appSecret().isEmpty());
        assertTrue(empty.allowedUserIds().isEmpty());
    }

    @Test
    void explicitlyDisabled() {
        assertFalse(parse("{ \"enabled\": false, \"verifyToken\": \"vt\" }").enabled());
    }

    @Test
    void anEmptyAllowListDeniesAnySenderButARestrictedOneGatesByWaId() {
        Spec open = parse("{ \"verifyToken\": \"vt\" }");
        assertFalse(open.isSenderAllowed("15559998888"),
                "#170 fail-closed: an empty allow-list denies any sender");

        Spec restricted = parse("{ \"allowedUserIds\": [\"15550001111\"] }");
        assertTrue(restricted.isSenderAllowed("15550001111"));
        assertFalse(restricted.isSenderAllowed("15557772222"), "an unlisted wa_id is refused");
        assertFalse(restricted.isSenderAllowed(null), "a null sender is never allowed when restricted");
    }

    @Test
    void anEmptyAllowListWithAllowAllUsersAdmitsAnySender() {
        Spec open = parse("{ \"allowAllUsers\": true }");
        assertTrue(open.isSenderAllowed("15559998888"),
                "explicit public mode opts back into admitting any sender (#170)");
    }
}
