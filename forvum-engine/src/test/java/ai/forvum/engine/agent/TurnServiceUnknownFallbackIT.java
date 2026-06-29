package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * #168 unknown-fallback case: an agent that declares an {@code identityId} fallback which names no
 * configured {@code identities/<id>.json} must <b>fail closed</b> for an unresolved user — never silently
 * degrade to the permissive anonymous default. Driven through the real {@link TurnService} dispatch path:
 * the turn is surfaced as a terminal {@code identity_unresolved} {@link ErrorEvent} with an actionable
 * message, and no tool runs (no escalation). Surefire-run (headless library, CLAUDE.md section 4).
 */
@QuarkusTest
@TestProfile(TurnServiceUnknownFallbackIT.UnknownFallbackHomeProfile.class)
class TurnServiceUnknownFallbackIT {

    @Inject
    TurnService turns;

    @Test
    void anUnresolvedUserWithAnUnknownAgentFallbackFailsClosedWithoutEscalating() {
        List<AgentEvent> events = new ArrayList<>();
        turns.dispatch(new ChannelMessage("web", "sess-x", "write something", Instant.now()), events::add);

        ErrorEvent error = events.stream()
                .filter(ErrorEvent.class::isInstance).map(ErrorEvent.class::cast)
                .findFirst().orElse(null);
        assertNotNull(error, "an unknown agent fallback identity must fail the turn closed, not degrade");
        assertEquals("identity_unresolved", error.code(),
                "the failure is an actionable identity_unresolved error, not a generic turn failure");
        assertTrue(error.message() != null && error.message().contains("ghost"),
                "the message names the undefined fallback identity so the operator can fix the spec");
        assertEquals(0L, ToolInvocationEntity.count(
                "sessionId = ?1 and toolName = ?2", "web:sess-x", "fs.write"),
                "a fail-closed turn must not run (or escalate) any tool");
    }

    /** Seeds {@code main} (belt = fs.write, scripted model) with an {@code identityId} fallback that
     * names {@code ghost} — for which NO {@code identities/ghost.json} exists. */
    public static class UnknownFallbackHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-unknown-fallback-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"scripted:m\", \"allowedTools\": [\"fs.write\"], "
                      + "\"identityId\": \"ghost\" }");
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
