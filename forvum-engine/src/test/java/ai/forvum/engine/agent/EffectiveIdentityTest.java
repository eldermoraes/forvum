package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * {@link EffectiveIdentity} canonical-constructor invariants (#168): a non-blank identity, a null role
 * list normalized to empty, and a defensive copy. Plain unit test — no Quarkus boot.
 */
class EffectiveIdentityTest {

    @Test
    void carriesItsIdentityAndRoles() {
        EffectiveIdentity effective = new EffectiveIdentity("alice", List.of("reader"));
        assertEquals("alice", effective.identityId());
        assertEquals(List.of("reader"), effective.roleNames());
    }

    @Test
    void nullRoleNamesNormalizeToEmpty() {
        assertEquals(List.of(), new EffectiveIdentity("alice", null).roleNames());
    }

    @Test
    void aBlankOrNullIdentityIsRejected() {
        assertThrows(IllegalStateException.class, () -> new EffectiveIdentity("  ", List.of()));
        assertThrows(IllegalStateException.class, () -> new EffectiveIdentity(null, List.of()));
    }
}
