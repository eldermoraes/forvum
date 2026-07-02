package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * #172 acceptance: a failed turn whose provider exception carries a secret, private paths, a tool
 * argument, and a prompt fragment must surface to the channel with NONE of them — only a stable category,
 * the correlation id, and (for a connection cause) the curated hint. The channel-bound event carries no
 * diagnostic fields. Driven through the real {@link ChannelTurnDriver} with {@code main} pinned to the
 * {@code leaky} provider.
 */
@QuarkusTest
@TestProfile(TurnServiceSafeErrorIT.LeakyHomeProfile.class)
class TurnServiceSafeErrorIT {

    @Inject
    ChannelTurnDriver driver;

    @Test
    void aFailedTurnLeaksNoSecretPathPromptOrToolArgToTheChannel() {
        List<AgentEvent> events = new ArrayList<>();

        driver.dispatch(new ChannelMessage("web", "sess-leak", "hello", Instant.now()), events::add);

        ErrorEvent error = assertInstanceOf(ErrorEvent.class, events.get(0),
                "a failed turn emits an ErrorEvent");
        String m = error.message();
        assertFalse(m.contains("sk-LEAKED1234567890abcdef"), "no secret in the channel-visible error");
        assertFalse(m.contains("/home/victim/.ssh/id_rsa"), "no private path");
        assertFalse(m.contains("/etc/shadow"), "no private path (nested cause)");
        assertFalse(m.contains("SECRET-PROMPT-XYZ"), "no prompt fragment");
        assertFalse(m.contains("\"path\":"), "no tool argument");
        assertEquals("turn_failed", error.code(), "a stable failure category");
        assertTrue(m.contains("ref: " + error.turnId()), "a correlation id");
        assertNull(error.stackTraceText(),
                "no diagnostic stack trace on the channel event (the latent leak is removed)");
    }

    public static class LeakyHomeProfile implements QuarkusTestProfile {
        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-leaky-turn-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"leaky:test-model\", \"allowedTools\": [] }");
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
