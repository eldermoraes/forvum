package ai.forvum.engine.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.engine.context.CurrentIdentity;
import ai.forvum.engine.persistence.MessageEntity;
import ai.forvum.engine.persistence.SessionEntity;
import ai.forvum.sdk.SessionAccess;
import ai.forvum.sdk.SessionMessage;
import ai.forvum.sdk.SessionSummary;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@link EngineSessionAccess} — the #189 engine seam behind the model-callable {@code agents.list} /
 * {@code sessions.list} / {@code sessions.history} / {@code sessions.send} tool. Proves the FAIL-CLOSED
 * identity scoping (#170/#167: an unbound or anonymous caller sees nothing; a session owned by another
 * identity is invisible to reads and denied to send with the same no-oracle diagnostic) and that a send
 * into a visible session dispatches a REAL turn through the turn driver (the in-process {@code fake}
 * provider replies, and the exchange is persisted into the target transcript). Runs over real SQLite.
 */
@QuarkusTest
@TestProfile(EngineSessionAccessIT.SessionsHomeProfile.class)
class EngineSessionAccessIT {

    @Inject
    SessionAccess access;

    @BeforeEach
    void clean() {
        QuarkusTransaction.requiringNew().run(() -> {
            MessageEntity.deleteAll();
            SessionEntity.deleteAll();
        });
    }

    // ---- agents.list ----

    @Test
    void agentIdsListsTheConfiguredAgentsForABoundIdentity() {
        List<String> ids = as("alice", access::agentIds);
        assertEquals(List.of("main"), ids, "the configured agents/main.json is listed");
    }

    @Test
    void agentIdsIsEmptyForAnUnboundOrAnonymousCaller() {
        assertTrue(access.agentIds().isEmpty(), "no identity binding -> fail closed, nothing visible");
        assertTrue(as("anonymous", access::agentIds).isEmpty(),
                "the unresolved anonymous identity sees nothing (fail closed)");
    }

    // ---- sessions.list ----

    @Test
    void sessionsAreScopedToTheCallersIdentity() {
        seedSession("web:sess-a", "alice", "web", 10L);
        seedSession("telegram:77", "bob", "telegram", 20L);

        List<SessionSummary> alice = as("alice", access::sessions);
        assertEquals(List.of("web:sess-a"), alice.stream().map(SessionSummary::id).toList(),
                "alice sees only her own session, never bob's");

        List<SessionSummary> bob = as("bob", access::sessions);
        assertEquals(List.of("telegram:77"), bob.stream().map(SessionSummary::id).toList(),
                "bob sees only his own session, never alice's");
    }

    @Test
    void sessionsAreEmptyForAnUnboundOrAnonymousCaller() {
        seedSession("web:sess-a", "alice", "web", 10L);

        assertTrue(access.sessions().isEmpty(), "no identity binding -> fail closed, no sessions");
        assertTrue(as("anonymous", access::sessions).isEmpty(),
                "the unresolved anonymous identity sees no sessions (fail closed)");
    }

    @Test
    void theSingleUserDefaultTenantSeesEverySession() {
        seedSession("web:sess-a", "alice", "web", 10L);
        seedSession("telegram:77", "bob", "telegram", 20L);

        List<SessionSummary> all = as(CurrentIdentity.DEFAULT_IDENTITY, access::sessions);
        assertEquals(2, all.size(),
                "the #53 single-user 'default' tenant is the namespace collapse — one operator sees all");
        assertEquals("telegram:77", all.get(0).id(), "sessions are ordered most recently active first");
    }

    // ---- sessions.history ----

    @Test
    void historyReturnsTheTranscriptOldestFirstWithinTheIdentityScope() {
        seedSession("web:sess-a", "alice", "web", 10L);
        seedMessage("web:sess-a", "user", "hello");
        seedMessage("web:sess-a", "assistant", "pong");

        List<SessionMessage> history = as("alice", () -> access.history("web:sess-a", 10));
        assertEquals(List.of("hello", "pong"), history.stream().map(SessionMessage::content).toList(),
                "the transcript is returned oldest first");
        assertEquals("user", history.get(0).role());
    }

    @Test
    void historyKeepsOnlyTheMostRecentLimitMessages() {
        seedSession("web:sess-a", "alice", "web", 10L);
        seedMessage("web:sess-a", "user", "first");
        seedMessage("web:sess-a", "assistant", "second");
        seedMessage("web:sess-a", "user", "third");

        List<SessionMessage> history = as("alice", () -> access.history("web:sess-a", 2));
        assertEquals(List.of("second", "third"), history.stream().map(SessionMessage::content).toList(),
                "the window is the MOST RECENT limit messages, still rendered oldest first");
    }

    @Test
    void historyNeverSurfacesInternalScratchpadBlocks() {
        seedSession("web:sess-a", "alice", "web", 10L);
        seedMessage("web:sess-a", "user", "hello");
        seedBlock("web:sess-a", "tool", "PLAN v1", "plan");
        seedBlock("web:sess-a", "assistant", "thinking...", "turn_reasoning");
        seedBlock("web:sess-a", "tool", "fs.read result", "tool_execution");
        seedBlock("web:sess-a", "assistant", "draft artifact", "turn_artifact");
        seedMessage("web:sess-a", "assistant", "pong");

        List<SessionMessage> history = as("alice", () -> access.history("web:sess-a", 10));
        assertEquals(List.of("hello", "pong"), history.stream().map(SessionMessage::content).toList(),
                "only turn_message transcript rows surface; plan/reasoning/tool/artifact blocks do not");
    }

    @Test
    void historyOfAnotherIdentitysSessionFailsLikeANonexistentOne() {
        seedSession("web:sess-a", "alice", "web", 10L);
        seedMessage("web:sess-a", "user", "hello");

        IllegalArgumentException crossTenant = assertThrows(IllegalArgumentException.class,
                () -> as("bob", () -> access.history("web:sess-a", 10)),
                "bob must never read alice's transcript — fail closed");
        IllegalArgumentException nonexistent = assertThrows(IllegalArgumentException.class,
                () -> as("bob", () -> access.history("web:no-such", 10)));
        assertEquals(
                nonexistent.getMessage().replace("web:no-such", "X"),
                crossTenant.getMessage().replace("web:sess-a", "X"),
                "an invisible session and a nonexistent one fail identically — no cross-tenant existence oracle");
    }

    @Test
    void historyIsDeniedForAnUnboundCaller() {
        seedSession("web:sess-a", "alice", "web", 10L);
        assertThrows(IllegalArgumentException.class, () -> access.history("web:sess-a", 10),
                "no identity binding -> the read is denied, not defaulted");
    }

    // ---- sessions.send ----

    @Test
    void sendDeliversATurnIntoAVisibleSessionAndPersistsTheExchange() {
        // alice owns web:sess-a (identities/alice.json maps web -> sess-a, so the nested turn re-resolves
        // to alice and the session row keeps her identity).
        seedSession("web:sess-a", "alice", "web", 10L);

        String reply = as("alice", () -> access.send("web:sess-a", "ping from another session"));

        assertEquals("pong", reply, "the target session's agent (fake provider) replies through the turn driver");
        List<MessageEntity> rows = MessageEntity.list("sessionId = ?1 order by id", "web:sess-a");
        assertTrue(rows.stream().anyMatch(m -> "user".equals(m.role)
                        && m.content.contains("ping from another session")),
                "the delivered message is appended to the target transcript as a user message");
        assertTrue(rows.stream().anyMatch(m -> "assistant".equals(m.role) && "pong".equals(m.content)),
                "the target agent's reply is appended to the target transcript");
    }

    @Test
    void sendAcrossTheIdentityBoundaryIsDeniedBeforeAnyTurnRuns() {
        seedSession("web:sess-a", "alice", "web", 10L);

        assertThrows(IllegalArgumentException.class,
                () -> as("bob", () -> access.send("web:sess-a", "sneaky")),
                "bob must never deliver into alice's session — fail closed");
        assertEquals(0L, MessageEntity.count("sessionId = ?1", "web:sess-a"),
                "the denied delivery ran no turn — the target transcript is untouched");
    }

    @Test
    void sendIsDeniedForAnUnboundOrAnonymousCaller() {
        seedSession("web:sess-a", "alice", "web", 10L);

        assertThrows(IllegalArgumentException.class, () -> access.send("web:sess-a", "hi"),
                "no identity binding -> delivery is denied");
        assertThrows(IllegalArgumentException.class,
                () -> as("anonymous", () -> access.send("web:sess-a", "hi")),
                "the unresolved anonymous identity cannot deliver anywhere (fail closed)");
    }

    @Test
    void sendIntoANonAddressableInternalSessionIsRefused() {
        // An internal-path row whose id does not embed its channel has no channelId:nativeUserId address.
        seedInternalSession("standalone-session", "alice");

        assertThrows(IllegalStateException.class,
                () -> as("alice", () -> access.send("standalone-session", "hi")),
                "a session not keyed channelId:nativeUserId cannot be dispatched into");
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

    private void seedInternalSession(String id, String identityId) {
        QuarkusTransaction.requiringNew().run(() -> {
            SessionEntity session = new SessionEntity();
            session.id = id;
            session.identityId = identityId;
            session.channelId = "internal";
            session.agentId = "main";
            session.startedAt = 1L;
            session.lastSeenAt = 1L;
            session.persist();
        });
    }

    private void seedMessage(String sessionId, String role, String content) {
        seedBlock(sessionId, role, content, "turn_message");
    }

    private void seedBlock(String sessionId, String role, String content, String blockType) {
        QuarkusTransaction.requiringNew().run(() -> {
            MessageEntity message = new MessageEntity();
            message.sessionId = sessionId;
            message.agentId = "main";
            message.role = role;
            message.content = content;
            message.blockType = blockType;
            message.createdAt = System.currentTimeMillis();
            message.persist();
        });
    }

    /** Seeds {@code main} pinned to the in-process fake provider + identity {@code alice} for (web, sess-a). */
    public static class SessionsHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-sessions-seam-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");
                Path identities = Files.createDirectories(home.resolve("identities"));
                Files.writeString(identities.resolve("alice.json"),
                        "{ \"displayName\": \"Alice\", \"channelAccounts\": { \"web\": \"sess-a\" } }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", HOME.toString());
        }
    }
}
