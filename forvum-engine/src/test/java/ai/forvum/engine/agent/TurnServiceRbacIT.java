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
 * P2-11 RBAC end-to-end through the real turn entry: {@link TurnService#dispatch} resolves the inbound
 * identity's roles, binds {@code CURRENT_EFFECTIVE_SCOPES}, and drives the M18 {@code SupervisorGraph}; a
 * {@code fs.write} tool call the model emits is gated by the executor on the SAME synchronous virtual
 * thread the binding lives on. This pins the dispatch-level wiring (and that the {@code ScopedValue}
 * survives the graph traversal to the executor) that the engine/app unit tests cover only piecewise — a
 * regression guard against the binding being dropped or a future langgraph4j async node dispatch silently
 * demoting every turn to belt-only.
 *
 * <p>Same agent (belt = {@code fs.write}), same scripted model (emits a {@code fs.write} call), two
 * identities: {@code restricted} (role {@code reader} → only {@code FS_READ}) is denied + audited;
 * {@code openweb} (no roles → permissive default) runs it. Surefire/Failsafe-run (headless library).
 */
@QuarkusTest
@TestProfile(TurnServiceRbacIT.RbacChannelHomeProfile.class)
class TurnServiceRbacIT {

    @Inject
    TurnService turns;

    @Test
    void aRoleRestrictedIdentityIsDeniedAnFsWriteToolDrivenThroughDispatch() {
        turns.dispatch(new ChannelMessage("web", "sess-r", "write something", Instant.now()), e -> { });

        assertEquals(1L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "web:sess-r", "denied", "fs.write"),
                "the restricted identity's fs.write call must be denied + audited through the dispatch path");
        assertEquals(0L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "web:sess-r", "ok", "fs.write"),
                "the denied call must never have run");
    }

    @Test
    void anIdentityWithoutRolesRunsTheSameToolDrivenThroughDispatch() {
        turns.dispatch(new ChannelMessage("web", "sess-o", "write something", Instant.now()), e -> { });

        assertEquals(1L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "web:sess-o", "ok", "fs.write"),
                "an identity with no roles gets the permissive default and the same in-belt tool runs");
        assertEquals(0L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "web:sess-o", "denied", "fs.write"),
                "the unrestricted identity is not scope-denied");
    }

    /** Seeds {@code main} (belt = fs.write, scripted model), a role-restricted {@code restricted} identity
     * mapped to (web, sess-r), an unrestricted {@code openweb} identity mapped to (web, sess-o), and a
     * {@code reader} role granting only FS_READ. */
    public static class RbacChannelHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-rbac-dispatch-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"scripted:m\", \"allowedTools\": [\"fs.write\"] }");
                Path roles = Files.createDirectories(home.resolve("roles"));
                Files.writeString(roles.resolve("reader.json"), "{ \"scopes\": [\"FS_READ\"] }");
                Path identities = Files.createDirectories(home.resolve("identities"));
                Files.writeString(identities.resolve("restricted.json"),
                        "{ \"displayName\": \"Restricted\", \"channelAccounts\": { \"web\": \"sess-r\" }, "
                      + "\"roles\": [\"reader\"] }");
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
