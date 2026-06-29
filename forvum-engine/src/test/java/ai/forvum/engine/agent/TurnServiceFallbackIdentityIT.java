package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.forvum.core.ChannelMessage;
import ai.forvum.engine.persistence.SessionEntity;
import ai.forvum.engine.persistence.ToolInvocationEntity;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * #168 resolved + fallback precedence, driven through the real {@link TurnService} dispatch path. The
 * agent declares an {@code identityId} fallback ({@code capped}, whose {@code reader} role grants only
 * {@code FS_READ}). The scripted model emits an {@code fs.write} call on every turn, so the effective
 * identity's scopes decide allow/deny:
 *
 * <ul>
 *   <li><b>resolved wins</b>: a mapped user ({@code sess-k} → {@code known}, no roles → permissive
 *       default) keeps the default and runs the write; its session is attributed to {@code known}, not
 *       the fallback.</li>
 *   <li><b>fallback applies</b>: an unmapped user falls to the agent's {@code capped} fallback, whose
 *       {@code reader} role denies the write; its session is attributed to {@code capped}, not the
 *       anonymous identity.</li>
 * </ul>
 *
 * Surefire-run (headless library, CLAUDE.md section 4).
 */
@QuarkusTest
@TestProfile(TurnServiceFallbackIdentityIT.FallbackHomeProfile.class)
class TurnServiceFallbackIdentityIT {

    @Inject
    TurnService turns;

    @Test
    void aResolvedUserTakesPrecedenceOverTheAgentFallbackAndRunsFsWrite() {
        turns.dispatch(new ChannelMessage("web", "sess-k", "write something", Instant.now()), e -> { });

        assertEquals(1L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "web:sess-k", "ok", "fs.write"),
                "a resolved identity wins over the agent fallback and keeps its permissive default");
        SessionEntity session = SessionEntity.findById("web:sess-k");
        assertEquals("known", session.identityId,
                "the session is attributed to the resolved identity, not the agent fallback");
    }

    @Test
    void anUnresolvedUserUsesTheAgentFallbackIdentityAndItsRoleCaps() {
        turns.dispatch(new ChannelMessage("web", "sess-x", "write something", Instant.now()), e -> { });

        assertEquals(1L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "web:sess-x", "denied", "fs.write"),
                "an unresolved user uses the agent fallback identity, whose reader role denies fs.write");
        assertEquals(0L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "web:sess-x", "ok", "fs.write"),
                "the fallback's reader role must not run the write");
        SessionEntity session = SessionEntity.findById("web:sess-x");
        assertEquals("capped", session.identityId,
                "the session is attributed to the fallback identity, not the anonymous identity");
    }

    /**
     * Seeds {@code main} (belt = fs.write, scripted model, fallback {@code identityId: capped}), the
     * {@code capped} fallback identity (role {@code reader}), a resolved {@code known} identity mapped to
     * (web, sess-k) with no roles, and a {@code reader} role granting only FS_READ.
     */
    public static class FallbackHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-fallback-identity-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"scripted:m\", \"allowedTools\": [\"fs.write\"], "
                      + "\"identityId\": \"capped\" }");
                Path roles = Files.createDirectories(home.resolve("roles"));
                Files.writeString(roles.resolve("reader.json"), "{ \"scopes\": [\"FS_READ\"] }");
                Path identities = Files.createDirectories(home.resolve("identities"));
                Files.writeString(identities.resolve("capped.json"),
                        "{ \"displayName\": \"Capped fallback\", \"roles\": [\"reader\"] }");
                Files.writeString(identities.resolve("known.json"),
                        "{ \"displayName\": \"Known\", \"channelAccounts\": { \"web\": \"sess-k\" } }");
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
