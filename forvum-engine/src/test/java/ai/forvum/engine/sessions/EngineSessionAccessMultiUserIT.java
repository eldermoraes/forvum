package ai.forvum.engine.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.engine.context.CurrentIdentity;
import ai.forvum.engine.persistence.MessageEntity;
import ai.forvum.engine.persistence.SessionEntity;
import ai.forvum.sdk.SessionAccess;
import ai.forvum.sdk.SessionSummary;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The #189-audit multi-user gate: with {@code forvum.multi-user.enabled=true} the seam's god-view
 * collapse is OFF — a caller who happens to resolve to the literal {@code "default"} identity is an
 * ordinary tenant (the gate keys on the TOGGLE, not the identity NAME), so it sees only its own
 * {@code sessions.identity_id} rows and cannot read or deliver into another tenant's session. The
 * single-user sibling suite ({@link EngineSessionAccessIT}) proves the byte-identical toggle-off
 * behavior; this profile proves the multi-user posture.
 */
@QuarkusTest
@TestProfile(EngineSessionAccessMultiUserIT.MultiUserProfile.class)
class EngineSessionAccessMultiUserIT {

    @Inject
    SessionAccess access;

    @BeforeEach
    void clean() {
        QuarkusTransaction.requiringNew().run(() -> {
            MessageEntity.deleteAll();
            SessionEntity.deleteAll();
        });
    }

    @Test
    void theDefaultIdentityIsAnOrdinaryTenantWhenMultiUserIsOn() {
        seedSession("web:sess-a", "alice", "web", 10L);
        seedSession("cli:local", CurrentIdentity.DEFAULT_IDENTITY, "cli", 20L);

        List<SessionSummary> visible = as(CurrentIdentity.DEFAULT_IDENTITY, access::sessions);
        assertEquals(List.of("cli:local"), visible.stream().map(SessionSummary::id).toList(),
                "multi-user on: 'default' sees ONLY identity_id='default' rows — no god-view by name");
    }

    @Test
    void theDefaultIdentityCannotReadAnotherTenantsTranscriptWhenMultiUserIsOn() {
        seedSession("web:sess-a", "alice", "web", 10L);

        assertThrows(IllegalArgumentException.class,
                () -> as(CurrentIdentity.DEFAULT_IDENTITY, () -> access.history("web:sess-a", 10)),
                "multi-user on: 'default' is not omniscient — alice's transcript is invisible to it");
    }

    @Test
    void theDefaultIdentityCannotDeliverIntoAnotherTenantsSessionWhenMultiUserIsOn() {
        seedSession("web:sess-a", "alice", "web", 10L);

        assertThrows(IllegalArgumentException.class,
                () -> as(CurrentIdentity.DEFAULT_IDENTITY, () -> access.send("web:sess-a", "sneaky")),
                "multi-user on: 'default' cannot relay into alice's session — fail closed");
        assertEquals(0L, MessageEntity.count("sessionId = ?1", "web:sess-a"),
                "the denied delivery ran no turn — the target transcript is untouched");
    }

    private <T> T as(String identity, Supplier<T> body) {
        return ScopedValue.where(CurrentIdentity.CURRENT_IDENTITY_ID, identity).call(body::get);
    }

    private void seedSession(String id, String identityId, String channelId, long lastSeenAt) {
        QuarkusTransaction.requiringNew().run(() -> {
            SessionEntity session = new SessionEntity();
            session.id = id;
            session.identityId = identityId;
            session.channelId = channelId;
            session.agentId = "main";
            session.startedAt = lastSeenAt;
            session.lastSeenAt = lastSeenAt;
            session.persist();
        });
    }

    /** The {@link EngineSessionAccessIT} home, with the multi-user toggle ON. */
    public static class MultiUserProfile extends EngineSessionAccessIT.SessionsHomeProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("forvum.multi-user.enabled", "true");
            return overrides;
        }
    }
}
