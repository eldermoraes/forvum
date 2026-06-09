package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.sdk.ChannelTurnDriver;

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
 * The terminal {@link ErrorEvent} of a failed turn must be actionable for a fresh install: when the
 * provider is unreachable (the model server is down / the base URL is wrong), the message surfaces the
 * deepest cause — not just the supervisor-graph wrapper — plus a hint naming the agent's configured
 * model, so the channel shows "is the model provider running?" instead of an opaque
 * "Supervisor graph failed for session ...".
 */
@QuarkusTest
@TestProfile(TurnServiceUnreachableProviderIT.UnreachableHomeProfile.class)
class TurnServiceUnreachableProviderIT {

    @Inject
    ChannelTurnDriver driver;

    @Test
    void theErrorMessageSurfacesTheRootCauseAndNamesTheConfiguredModel() {
        List<AgentEvent> events = new ArrayList<>();

        driver.dispatch(new ChannelMessage("web", "sess-u", "hello", Instant.now()), events::add);

        assertEquals(1, events.size(), "a failed turn emits exactly one terminal ErrorEvent");
        ErrorEvent error = assertInstanceOf(ErrorEvent.class, events.get(0));
        String message = error.message();
        assertTrue(message.contains("ConnectException"),
                () -> "the root cause is surfaced, got: " + message);
        assertTrue(message.contains("Is the model provider running?"),
                () -> "a connection failure carries the provider hint, got: " + message);
        assertTrue(message.contains("unreachable:test-model"),
                () -> "the hint names the configured model, got: " + message);
    }

    /** Seeds {@code main} pinned to the connection-refusing {@code unreachable} provider. */
    public static class UnreachableHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-unreachable-turn-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"unreachable:test-model\", \"allowedTools\": [] }");
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
