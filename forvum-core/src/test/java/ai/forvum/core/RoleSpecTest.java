package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * {@link RoleSpec}: a named authorization role granting a set of {@link PermissionScope}s. Name is a
 * non-blank token; {@code scopes} is defensively copied + immutable; null scope elements are rejected.
 */
class RoleSpecTest {

    @Test
    void acceptsValidAndExposesScopes() {
        RoleSpec role = new RoleSpec("reader", Set.of(PermissionScope.FS_READ));
        assertEquals("reader", role.name());
        assertEquals(Set.of(PermissionScope.FS_READ), role.scopes());
    }

    @Test
    void allowsEmptyScopes() {
        assertEquals(Set.of(), new RoleSpec("locked", Set.of()).scopes());
    }

    @Test
    void scopesAreDefensivelyCopied() {
        Set<PermissionScope> source = new HashSet<>();
        source.add(PermissionScope.FS_READ);
        RoleSpec role = new RoleSpec("reader", source);
        source.add(PermissionScope.FS_WRITE);
        assertFalse(role.scopes().contains(PermissionScope.FS_WRITE));
    }

    @Test
    void scopesAreImmutable() {
        RoleSpec role = new RoleSpec("reader", Set.of(PermissionScope.FS_READ));
        assertThrows(UnsupportedOperationException.class,
                () -> role.scopes().add(PermissionScope.FS_WRITE));
    }

    @Test
    void rejectsNullScopeElementWithTriageException() {
        // A hand-edited roles/<name>.json with a null entry must surface the module idiom
        // (IllegalStateException), not a bare NullPointerException from Set.copyOf.
        Set<PermissionScope> withNull = new HashSet<>();
        withNull.add(null);
        assertThrows(IllegalStateException.class, () -> new RoleSpec("reader", withNull));
    }

    @Test
    void rejectsInvalid() {
        assertThrows(IllegalStateException.class, () -> new RoleSpec(null, Set.of()));
        assertThrows(IllegalStateException.class, () -> new RoleSpec(" ", Set.of()));
        assertThrows(IllegalStateException.class, () -> new RoleSpec(" reader", Set.of()));
        assertThrows(IllegalStateException.class, () -> new RoleSpec("reader", null));
    }
}
