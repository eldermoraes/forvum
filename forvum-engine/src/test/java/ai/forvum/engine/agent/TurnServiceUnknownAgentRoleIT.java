package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.ErrorEvent;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * #167 fail-closed config error (acceptance #4): the {@code main} agent declares an UNKNOWN role (neither
 * a built-in nor a {@code roles/<name>.json}), so its role cap cannot be resolved. The turn must fail
 * CLOSED with a single terminal {@code role_unresolved} {@link ErrorEvent} — never silently fall back to
 * no-cap (which would leave the caller's full scopes in force) and never run a tool. The error message is
 * the actionable config diagnostic from {@code RoleRegistry} and carries no caller scopes. Surefire-run
 * (headless library).
 */
@QuarkusTest
@TestProfile(TurnServiceUnknownAgentRoleIT.UnknownRoleHomeProfile.class)
class TurnServiceUnknownAgentRoleIT {

    @Inject
    TurnService turns;

    @Test
    void anAgentWithAnUnknownRoleFailsClosedWithATerminalConfigError() {
        List<AgentEvent> events = new ArrayList<>();
        turns.dispatch(new ChannelMessage("web", "sess-g", "write something", Instant.now()), events::add);

        assertEquals(1, events.size(),
                "a fail-closed turn emits exactly one terminal event, got: " + events);
        ErrorEvent error = assertInstanceOf(ErrorEvent.class, events.get(0),
                "an unknown agent role surfaces as a terminal ErrorEvent");
        assertEquals("role_unresolved", error.code(),
                "the fail-closed code identifies the unresolved role cap");
        assertEquals(0L, ToolInvocationEntity.count("sessionId = ?1", "web:sess-g"),
                "no tool runs when the agent's role cap cannot be resolved");
    }

    /** Seeds {@code main} declaring an undefined {@code ghost-role}, with a permissive {@code openweb} caller. */
    public static class UnknownRoleHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-unknown-role-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"scripted:m\", \"allowedTools\": [\"fs.write\"], "
                      + "\"roles\": [\"ghost-role\"] }");
                Path identities = Files.createDirectories(home.resolve("identities"));
                Files.writeString(identities.resolve("openweb.json"),
                        "{ \"displayName\": \"Open\", \"channelAccounts\": { \"web\": \"sess-g\" } }");
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
