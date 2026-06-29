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
 * #168 absent-fallback case: an unresolved channel user, driven through the real {@link TurnService}
 * dispatch path against an agent that declares no {@code identityId} fallback, must fall to the
 * deliberately restricted {@code anonymous} role — NOT the permissive {@code default-user} all-scopes
 * default. The agent's belt is {@code fs.write} and the scripted model emits an {@code fs.write} call;
 * the anonymous role grants no scopes, so the executor denies + audits it. This is the security-relevant
 * regression: before #168 an unmapped user became permissive {@code anonymous} (= every scope) and the
 * write ran. Surefire-run (headless library, CLAUDE.md section 4).
 */
@QuarkusTest
@TestProfile(TurnServiceAnonymousIdentityIT.AnonymousHomeProfile.class)
class TurnServiceAnonymousIdentityIT {

    @Inject
    TurnService turns;

    @Test
    void anUnresolvedUserFallsToTheRestrictedAnonymousRoleAndIsDeniedFsWrite() {
        turns.dispatch(new ChannelMessage("web", "sess-x", "write something", Instant.now()), e -> { });

        assertEquals(1L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "web:sess-x", "denied", "fs.write"),
                "an unresolved user must fall to the restricted anonymous role and be denied fs.write");
        assertEquals(0L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "web:sess-x", "ok", "fs.write"),
                "the unresolved user must never escalate to the permissive default and run the write");
    }

    @Test
    void theSessionIsAttributedToTheAnonymousIdentity() {
        turns.dispatch(new ChannelMessage("web", "sess-y", "hello", Instant.now()), e -> { });

        SessionEntity session = SessionEntity.findById("web:sess-y");
        assertEquals("anonymous", session.identityId,
                "an unresolved user's session is attributed to the anonymous identity, not a real one");
    }

    /** Seeds {@code main} (belt = fs.write, scripted model) with NO {@code identityId} fallback. */
    public static class AnonymousHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-anon-identity-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"scripted:m\", \"allowedTools\": [\"fs.write\"] }");
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
