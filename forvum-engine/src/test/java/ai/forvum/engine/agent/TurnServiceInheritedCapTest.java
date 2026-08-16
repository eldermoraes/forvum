package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.PermissionScope;
import ai.forvum.engine.context.CurrentIdentity;

import org.junit.jupiter.api.Test;

import java.util.Set;

/**
 * The #189-audit inherited-cap intersection ({@link TurnService#applyInheritedCap}): a relayed dispatch
 * ({@code sessions.send}) binds the CALLING turn's effective scopes as
 * {@link CurrentIdentity#INHERITED_SCOPE_CAP}, and the nested turn's scopes must be intersected with it —
 * the #166 device cap survives the relay, and a cap can only ever RESTRICT (#167). A plain unit test (no
 * Quarkus boot): the helper reads only the ScopedValue.
 */
class TurnServiceInheritedCapTest {

    @Test
    void unboundCapPassesScopesThroughUnchanged() {
        Set<PermissionScope> scopes = Set.of(PermissionScope.FS_READ, PermissionScope.SESSION_WRITE);
        assertSame(scopes, TurnService.applyInheritedCap(scopes),
                "every direct (non-relayed) turn carries no cap — pass-through");
    }

    @Test
    void boundCapIntersectsTheNestedTurnsScopes() {
        Set<PermissionScope> callerScopes = Set.of(PermissionScope.FS_READ);
        Set<PermissionScope> nestedScopes = Set.of(
                PermissionScope.FS_READ, PermissionScope.FS_WRITE, PermissionScope.SHELL_EXEC);

        Set<PermissionScope> capped = ScopedValue
                .where(CurrentIdentity.INHERITED_SCOPE_CAP, callerScopes)
                .call(() -> TurnService.applyInheritedCap(nestedScopes));

        assertEquals(Set.of(PermissionScope.FS_READ), capped,
                "the nested turn runs no wider than its relayer — the device cap survives the relay");
    }

    @Test
    void anEmptyCapYieldsNoScopesAtAll() {
        Set<PermissionScope> capped = ScopedValue
                .where(CurrentIdentity.INHERITED_SCOPE_CAP, Set.<PermissionScope>of())
                .call(() -> TurnService.applyInheritedCap(
                        Set.of(PermissionScope.FS_READ, PermissionScope.FS_WRITE)));

        assertTrue(capped.isEmpty(),
                "a scope-less relayer relays a scope-less turn — empty means empty, never fail-open");
    }
}
