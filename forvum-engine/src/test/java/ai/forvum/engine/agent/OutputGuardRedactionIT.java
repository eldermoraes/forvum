package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.TokenDelta;

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
 * The default-on {@code SecretRedactionGuard} masks a model-leaked secret at the pre-channel-emit seam:
 * with {@code main} pinned to the {@code leak} provider (whose reply embeds an {@code sk-ant-...} key), the
 * channel sees the masked text in BOTH the {@link TokenDelta} and the terminal {@link Done} — never the
 * raw secret — and the turn still completes (non-fatal Redacted).
 */
@QuarkusTest
@TestProfile(OutputGuardRedactionIT.LeakHomeProfile.class)
class OutputGuardRedactionIT {

    @Inject
    TurnService turns;

    @Test
    void redactsALeakedSecretInTheEgressButStillCompletesTheTurn() {
        List<AgentEvent> events = new ArrayList<>();

        turns.dispatch(new ChannelMessage("web", "sess-a", "what is the key?", Instant.now()), events::add);

        assertEquals(2, events.size(), "a Redacted egress is non-fatal: TokenDelta then Done");
        String delta = assertInstanceOf(TokenDelta.class, events.get(0)).text();
        String done = assertInstanceOf(Done.class, events.get(1)).finalMessage();
        assertTrue(delta.contains("sk-ant-***"), "the prefix survives, the body is masked: " + delta);
        assertFalse(delta.contains(SecretLeakingModelProvider.LEAKED_SECRET), "no raw secret in TokenDelta");
        assertFalse(done.contains(SecretLeakingModelProvider.LEAKED_SECRET), "no raw secret in Done");
        assertEquals(delta, done, "the redacted text is consistent across both events");
    }

    /** Seeds {@code main} pinned to the secret-leaking provider for (web, sess-a). */
    public static class LeakHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-outputguard-redact-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"leak:test-model\", \"allowedTools\": [] }");
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
