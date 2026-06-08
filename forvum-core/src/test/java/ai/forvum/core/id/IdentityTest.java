package ai.forvum.core.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link Identity}: id/displayName non-blank; {@code channelAccounts} defensively copied + immutable;
 * {@code roles} additive (defaults to empty for backward compatibility), defensively copied + immutable.
 */
class IdentityTest {

    @Test
    void acceptsValidAndExposesAccounts() {
        Identity id = new Identity("user-1", "Elder", Map.of("telegram", "12345"));
        assertEquals("12345", id.channelAccounts().get("telegram"));
    }

    @Test
    void channelAccountsAreDefensivelyCopied() {
        Map<String, String> source = new HashMap<>();
        source.put("telegram", "12345");
        Identity id = new Identity("user-1", "Elder", source);
        source.put("tui", "local");
        assertFalse(id.channelAccounts().containsKey("tui"));
    }

    @Test
    void channelAccountsAreImmutable() {
        Identity id = new Identity("user-1", "Elder", Map.of("telegram", "1"));
        assertThrows(UnsupportedOperationException.class, () -> id.channelAccounts().put("x", "y"));
    }

    @Test
    void allowsEmptyAccounts() {
        new Identity("user-1", "Elder", Map.of());
    }

    @Test
    void rejectsNullAccountValueWithTriageException() {
        // A hand-edited identities/<id>.json with {"telegram": null} must surface the module idiom
        // (IllegalStateException), not a bare NullPointerException from Map.copyOf.
        Map<String, String> withNull = new HashMap<>();
        withNull.put("telegram", null);
        assertThrows(IllegalStateException.class, () -> new Identity("user-1", "Elder", withNull));
    }

    @Test
    void rejectsInvalid() {
        assertThrows(IllegalStateException.class, () -> new Identity(null, "D", Map.of()));
        assertThrows(IllegalStateException.class, () -> new Identity(" ", "D", Map.of()));
        assertThrows(IllegalStateException.class, () -> new Identity(" user", "D", Map.of()));
        assertThrows(IllegalStateException.class, () -> new Identity("user", null, Map.of()));
        assertThrows(IllegalStateException.class, () -> new Identity("user", " ", Map.of()));
        assertThrows(IllegalStateException.class, () -> new Identity("user", "D", null));
    }

    @Test
    void threeArgConstructorDefaultsRolesToEmpty() {
        // Backward compatibility: an identities/<id>.json predating RBAC (no 'roles') is unrestricted.
        assertTrue(new Identity("user-1", "Elder", Map.of()).roles().isEmpty());
    }

    @Test
    void rolesDefaultToEmptyWhenNull() {
        assertTrue(new Identity("user-1", "Elder", Map.of(), null).roles().isEmpty());
    }

    @Test
    void exposesDeclaredRoles() {
        assertEquals(List.of("reader", "writer"),
                new Identity("user-1", "Elder", Map.of(), List.of("reader", "writer")).roles());
    }

    @Test
    void rolesAreDefensivelyCopied() {
        List<String> source = new ArrayList<>();
        source.add("reader");
        Identity id = new Identity("user-1", "Elder", Map.of(), source);
        source.add("admin");
        assertFalse(id.roles().contains("admin"));
    }

    @Test
    void rolesAreImmutable() {
        Identity id = new Identity("user-1", "Elder", Map.of(), List.of("reader"));
        assertThrows(UnsupportedOperationException.class, () -> id.roles().add("admin"));
    }

    @Test
    void rejectsBlankOrNullRoleEntry() {
        assertThrows(IllegalStateException.class,
                () -> new Identity("user-1", "Elder", Map.of(), Arrays.asList(" ")));
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(IllegalStateException.class,
                () -> new Identity("user-1", "Elder", Map.of(), withNull));
    }
}
