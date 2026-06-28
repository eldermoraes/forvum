package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.forvum.app.OperatorAuthFailClosed.Posture;

import org.junit.jupiter.api.Test;

/**
 * Pure unit cover for the #165 fail-closed decision ({@link OperatorAuthFailClosed#posture}). Asserts both
 * directions so deleting the {@code serverChannelUp} guard is caught: an exposed server channel without a
 * credential FAILS CLOSED, a non-server (interactive) run without one only WARNS, and a configured
 * credential is OK either way.
 */
class OperatorAuthFailClosedTest {

    @Test
    void exposedServerWithoutCredentialFailsClosed() {
        assertEquals(Posture.FAIL_CLOSED, OperatorAuthFailClosed.posture(true, false));
    }

    @Test
    void interactiveRunWithoutCredentialOnlyWarns() {
        assertEquals(Posture.WARN, OperatorAuthFailClosed.posture(false, false));
    }

    @Test
    void aConfiguredCredentialIsOkWhetherOrNotAServerIsUp() {
        assertEquals(Posture.OK, OperatorAuthFailClosed.posture(true, true));
        assertEquals(Posture.OK, OperatorAuthFailClosed.posture(false, true));
    }
}
