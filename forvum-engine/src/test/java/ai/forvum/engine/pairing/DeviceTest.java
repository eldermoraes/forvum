package ai.forvum.engine.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.PermissionScope;

import org.junit.jupiter.api.Test;

import java.util.Set;

/** The {@link Device} scope-governance invariants (P2-PAIR-SCOPE #44). */
class DeviceTest {

    @Test
    void theFourArgFormDefaultsScopesEmptyAndReasonNull() {
        Device d = new Device("phone", "tok", "alice", false);
        assertTrue(d.requestedScopes().isEmpty());
        assertTrue(d.approvedScopes().isEmpty());
        assertEquals(null, d.decisionReason());
        assertFalse(d.hasScopeDrift(), "no requested scopes never drifts");
    }

    @Test
    void driftsWhenARequestedScopeIsNotApproved() {
        Device d = new Device("phone", "", "alice", false,
                Set.of(PermissionScope.FS_READ, PermissionScope.FS_WRITE),
                Set.of(PermissionScope.FS_READ), "partial");
        assertTrue(d.hasScopeDrift());
    }

    @Test
    void doesNotDriftWhenApprovedCoversRequested() {
        Device d = new Device("phone", "", "alice", false,
                Set.of(PermissionScope.FS_READ),
                Set.of(PermissionScope.FS_READ, PermissionScope.FS_WRITE), "ok");
        assertFalse(d.hasScopeDrift(), "approving a superset of requested is settled");
    }

    @Test
    void scopeSetsAreDefensivelyImmutable() {
        Device d = new Device("phone", "", "alice", false,
                Set.of(PermissionScope.FS_READ), Set.of(), null);
        assertThrows(UnsupportedOperationException.class,
                () -> d.requestedScopes().add(PermissionScope.FS_WRITE));
    }
}
