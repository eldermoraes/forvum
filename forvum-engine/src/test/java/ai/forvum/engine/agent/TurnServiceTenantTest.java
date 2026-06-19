package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import ai.forvum.engine.context.CurrentIdentity;

/**
 * The #53 multi-user toggle ternary ({@link TurnService#tenantIdentity}): off binds the shared
 * {@code "default"} tenant (byte-identical single-user), on binds the RESOLVED identity as the memory
 * tenant key. A plain unit test (no Quarkus boot — {@code tenantIdentity} reads only the
 * {@code multiUserEnabled} flag) red-checking the on-switch the dispatch IT suite never exercises (every
 * IT runs with the default {@code false}); inverting the ternary fails the on case here.
 */
class TurnServiceTenantTest {

    @Test
    void multiUserOffBindsTheSharedDefaultTenant() {
        TurnService service = new TurnService();
        service.multiUserEnabled = false;
        assertEquals(CurrentIdentity.DEFAULT_IDENTITY, service.tenantIdentity("alice"),
                "single-user (toggle off) binds the shared 'default' namespace");
    }

    @Test
    void multiUserOnBindsTheResolvedIdentity() {
        TurnService service = new TurnService();
        service.multiUserEnabled = true;
        assertEquals("alice", service.tenantIdentity("alice"),
                "multi-user (toggle on) binds the resolved identity, not 'default' — the isolation on-switch");
    }
}
