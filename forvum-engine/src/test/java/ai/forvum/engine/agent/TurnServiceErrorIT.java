package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 * A failed turn must be surfaced to the channel as a terminal {@link ErrorEvent}, NOT propagated as an
 * exception — a self-driving channel (the WebSocket endpoint) would otherwise let the exception escape
 * its callback and the framework would close the connection with nothing shown to the user. With
 * {@code main} pinned to the always-throwing {@code boom} provider, {@link ChannelTurnDriver#dispatch}
 * catches the failure and emits exactly one {@link ErrorEvent}.
 */
@QuarkusTest
@TestProfile(TurnServiceErrorIT.BoomHomeProfile.class)
class TurnServiceErrorIT {

    @Inject
    ChannelTurnDriver driver;

    @Test
    void dispatchEmitsAnErrorEventWhenTheTurnFailsInsteadOfThrowing() {
        List<AgentEvent> events = new ArrayList<>();

        driver.dispatch(new ChannelMessage("web", "sess-x", "hello", Instant.now()), events::add);

        assertEquals(1, events.size(), "a failed turn emits exactly one terminal ErrorEvent");
        ErrorEvent error = assertInstanceOf(ErrorEvent.class, events.get(0),
                "the failure is surfaced as an ErrorEvent, not propagated");
        assertNotNull(error.turnId(), "the ErrorEvent carries the bound turn id");
        assertNotNull(error.message(), "the ErrorEvent carries the failure message");
    }

    /** Seeds {@code main} pinned to the always-throwing {@code boom} provider. */
    public static class BoomHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-boom-turn-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"boom:test-model\", \"allowedTools\": [] }");
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
