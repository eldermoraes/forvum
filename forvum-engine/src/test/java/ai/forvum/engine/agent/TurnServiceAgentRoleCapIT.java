package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.forvum.core.ChannelMessage;
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
 * #167 agent-level role cap end-to-end through {@link TurnService#dispatch}: the canonical {@code main}
 * agent declares {@code roles:["reader"]} (FS_READ only), so even a PERMISSIVE caller (no roles &rarr; the
 * permissive {@code default-user}, every scope) is capped to FS_READ for this agent. The scripted model
 * emits an {@code fs.write} call; the effective scope set is {@code callerScopes ∩ {FS_READ}} which omits
 * FS_WRITE, so the executor denies + audits it on the SAME synchronous virtual thread the binding lives on.
 *
 * <p>Contrast with {@link TurnServiceRbacIT#anIdentityWithoutRolesRunsTheSameToolDrivenThroughDispatch}
 * (same permissive caller, {@code main} with NO roles &rarr; no cap &rarr; the write runs): the ONLY
 * difference is the agent's declared role ceiling, proving the cap — not the caller — is what restricts
 * (acceptance #1: "a caller cannot use a scope omitted by the selected agent's role ceiling").
 * Surefire-run (headless library, CLAUDE.md section 4).
 */
@QuarkusTest
@TestProfile(TurnServiceAgentRoleCapIT.AgentRoleCapHomeProfile.class)
class TurnServiceAgentRoleCapIT {

    @Inject
    TurnService turns;

    @Test
    void anAgentRoleCapDeniesAToolThatThePermissiveCallerCouldOtherwiseUse() {
        turns.dispatch(new ChannelMessage("web", "sess-o", "write something", Instant.now()), e -> { });

        assertEquals(1L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "web:sess-o", "denied", "fs.write"),
                "the agent's reader cap must deny fs.write even though the caller is the permissive default");
        assertEquals(0L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "web:sess-o", "ok", "fs.write"),
                "the capped call must never run");
    }

    /**
     * Seeds {@code main} with {@code roles:["reader"]} (FS_READ), belt {@code fs.write}, the scripted model;
     * a permissive {@code openweb} identity (no roles) mapped to (web, sess-o); a {@code reader} role file
     * granting only FS_READ.
     */
    public static class AgentRoleCapHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-agent-rolecap-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"scripted:m\", \"allowedTools\": [\"fs.write\"], "
                      + "\"roles\": [\"reader\"] }");
                Path roles = Files.createDirectories(home.resolve("roles"));
                Files.writeString(roles.resolve("reader.json"), "{ \"scopes\": [\"FS_READ\"] }");
                Path identities = Files.createDirectories(home.resolve("identities"));
                Files.writeString(identities.resolve("openweb.json"),
                        "{ \"displayName\": \"Open\", \"channelAccounts\": { \"web\": \"sess-o\" } }");
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
