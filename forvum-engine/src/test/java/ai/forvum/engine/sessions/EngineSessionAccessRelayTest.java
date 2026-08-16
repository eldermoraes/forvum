package ai.forvum.engine.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.PermissionScope;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.context.CurrentIdentity;
import ai.forvum.engine.persistence.SessionEntity;
import ai.forvum.sdk.ApprovalContext;
import ai.forvum.sdk.ChannelTurnDriver;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Unit test of the #189-audit relay bindings ({@link EngineSessionAccess#dispatchRelayed}): the nested
 * dispatch must (a) carry the CALLING turn's effective scopes as
 * {@link CurrentIdentity#INHERITED_SCOPE_CAP} — the #166 device cap survives the relay — or no cap when
 * the caller bound none; (b) bind {@link ApprovalContext#NON_INTERACTIVE} so a confirm-required tool in
 * the target turn denies at once instead of parking forever (D6.5); and (c) prefix the delivered content
 * with the provenance marker naming the calling session (D6.6). Direct instantiation with a recording
 * stub {@link ChannelTurnDriver} and a hand-built {@link SessionEntity} — no container, no DB.
 */
class EngineSessionAccessRelayTest {

    /** Records the ScopedValue bindings VISIBLE INSIDE the nested dispatch — the relay contract. */
    static final class RecordingDriver implements ChannelTurnDriver {
        ChannelMessage delivered;
        boolean capBound;
        Set<PermissionScope> cap;
        boolean nonInteractive;

        @Override
        public void dispatch(ChannelMessage message, Consumer<AgentEvent> sink) {
            this.delivered = message;
            this.capBound = CurrentIdentity.INHERITED_SCOPE_CAP.isBound();
            this.cap = capBound ? CurrentIdentity.INHERITED_SCOPE_CAP.get() : null;
            this.nonInteractive = ApprovalContext.NON_INTERACTIVE.isBound()
                    && Boolean.TRUE.equals(ApprovalContext.NON_INTERACTIVE.get());
        }
    }

    private static SessionEntity target() {
        SessionEntity target = new SessionEntity();
        target.id = "telegram:77";
        target.channelId = "telegram";
        target.identityId = "bob";
        target.agentId = "main";
        return target;
    }

    private static EngineSessionAccess accessWith(RecordingDriver driver) {
        EngineSessionAccess access = new EngineSessionAccess();
        access.turns = driver;
        return access;
    }

    @Test
    void relayPropagatesTheCallersEffectiveScopesAsTheInheritedCap() {
        RecordingDriver driver = new RecordingDriver();
        Set<PermissionScope> callerScopes = Set.of(PermissionScope.SESSION_WRITE);

        ScopedValue.where(CurrentIdentity.CURRENT_EFFECTIVE_SCOPES, callerScopes)
                .run(() -> accessWith(driver).dispatchRelayed(target(), "hello"));

        assertTrue(driver.capBound, "the caller's effective scopes MUST cross the relay as the cap");
        assertEquals(callerScopes, driver.cap,
                "the cap is the caller's exact effective set — the #166 device cap is never dropped");
    }

    @Test
    void relayWithoutACallingTurnBindsNoCap() {
        RecordingDriver driver = new RecordingDriver();

        accessWith(driver).dispatchRelayed(target(), "hello");

        assertFalse(driver.capBound,
                "a non-turn caller (no CURRENT_EFFECTIVE_SCOPES) inherits no cap — the nested turn "
              + "resolves its own scopes as any direct turn would");
    }

    @Test
    void relayIsNonInteractiveAndCarriesTheProvenancePrefix() {
        RecordingDriver driver = new RecordingDriver();

        ScopedValue.where(CurrentAgent.CURRENT_SESSION_ID, "web:sess-a")
                .run(() -> accessWith(driver).dispatchRelayed(target(), "ping"));

        assertTrue(driver.nonInteractive,
                "a relayed turn has no approval surface — NON_INTERACTIVE denies confirm-required tools at once");
        assertEquals("telegram", driver.delivered.channelId());
        assertEquals("77", driver.delivered.nativeUserId());
        assertEquals("[relayed via sessions.send from session 'web:sess-a'] ping",
                driver.delivered.content(),
                "the target transcript records the message as relayed, naming the calling session (D6.6)");
    }

    @Test
    void relayWithoutACallingSessionStillMarksTheProvenance() {
        RecordingDriver driver = new RecordingDriver();

        accessWith(driver).dispatchRelayed(target(), "ping");

        assertEquals("[relayed via sessions.send] ping", driver.delivered.content(),
                "even a non-turn caller's delivery is marked as relayed");
    }
}
