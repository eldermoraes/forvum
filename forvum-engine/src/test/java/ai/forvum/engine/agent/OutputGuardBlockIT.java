package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.ErrorEvent;

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
import java.util.Set;

/**
 * A {@code Blocked} disposition ends the turn on the FILTERED path: with the {@link BlockingOutputGuard}
 * enabled (it always Blocks), {@link TurnService} suppresses the egress and emits exactly one terminal
 * {@link ErrorEvent} with {@code code = "output_filtered"} — never a TokenDelta/Done leaking the candidate.
 */
@QuarkusTest
@TestProfile(OutputGuardBlockIT.BlockHomeProfile.class)
class OutputGuardBlockIT {

    @Inject
    TurnService turns;

    @Test
    void aBlockedEgressEndsTheTurnAsOutputFiltered() {
        List<AgentEvent> events = new ArrayList<>();

        turns.dispatch(new ChannelMessage("web", "sess-a", "hello", Instant.now()), events::add);

        assertEquals(1, events.size(), "a Blocked egress emits exactly one terminal ErrorEvent");
        ErrorEvent error = assertInstanceOf(ErrorEvent.class, events.get(0));
        assertEquals("output_filtered", error.code());
        assertNotNull(error.turnId());
    }

    /** Seeds {@code main} on the fake provider AND enables the always-Blocking guard. */
    public static class BlockHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-outputguard-block-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(BlockingOutputGuard.class);
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", HOME.toString());
        }
    }
}
