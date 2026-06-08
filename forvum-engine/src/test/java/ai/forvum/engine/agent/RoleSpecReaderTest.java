package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.RoleSpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

/**
 * {@link RoleSpecReader}: binds {@code roles/<name>.json} to a {@link RoleSpec}. The {@code scopes} array
 * is required (empty is allowed = a role granting nothing); an unknown scope is rejected with a contextual
 * exception (via {@link PermissionScope#fromName}). Property-style: every scope round-trips through the
 * reader (JUnit 5 {@code @EnumSource}, no third-party lib).
 */
class RoleSpecReaderTest {

    private final RoleSpecReader reader = new RoleSpecReader();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void parsesAScopesArray() throws Exception {
        RoleSpec role = reader.parse("reader", json("{\"scopes\":[\"FS_READ\"]}"));
        assertEquals("reader", role.name());
        assertEquals(Set.of(PermissionScope.FS_READ), role.scopes());
    }

    @Test
    void parsesMultipleScopes() throws Exception {
        RoleSpec role = reader.parse("rw", json("{\"scopes\":[\"FS_READ\",\"FS_WRITE\"]}"));
        assertEquals(Set.of(PermissionScope.FS_READ, PermissionScope.FS_WRITE), role.scopes());
    }

    @Test
    void allowsAnEmptyScopesArray() throws Exception {
        assertEquals(Set.of(), reader.parse("locked", json("{\"scopes\":[]}")).scopes());
    }

    @ParameterizedTest
    @EnumSource(PermissionScope.class)
    void everyScopeRoundTripsThroughTheReader(PermissionScope scope) throws Exception {
        RoleSpec role = reader.parse("r", json("{\"scopes\":[\"" + scope.name() + "\"]}"));
        assertTrue(role.scopes().contains(scope));
    }

    @Test
    void rejectsAMissingScopesArray() throws Exception {
        assertThrows(IllegalStateException.class, () -> reader.parse("bad", json("{}")));
    }

    @Test
    void rejectsANonArrayScopes() throws Exception {
        assertThrows(IllegalStateException.class, () -> reader.parse("bad", json("{\"scopes\":\"FS_READ\"}")));
    }

    @Test
    void rejectsAnUnknownScope() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> reader.parse("bad", json("{\"scopes\":[\"FS_TELEPORT\"]}")));
    }
}
