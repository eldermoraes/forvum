package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.TokenDelta;
import ai.forvum.engine.persistence.SessionEntity;

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
 * The channel turn-driver facade (ULTRAPLAN section 5.3 / 5.5): a channel hands a {@link ChannelMessage}
 * to {@link TurnService}, which resolves identity, binds {@code CURRENT_AGENT}/{@code CURRENT_TURN},
 * drives the single-shot {@code Agent.respond}, and renders the turn as a stream of {@link AgentEvent}
 * to the supplied sink — Option B (single-shot adaptation): one {@link TokenDelta} then {@link Done}
 * (true per-token streaming arrives with the M18 SupervisorGraph). The {@code main} agent is pinned to
 * the in-process {@code fake} provider so the turn is exercisable without a real LLM.
 */
@QuarkusTest
@TestProfile(TurnServiceIT.ChannelHomeProfile.class)
class TurnServiceIT {

    @Inject
    TurnService turns;

    @Test
    void dispatchDrivesATurnAndEmitsTokenDeltaThenDone() {
        List<AgentEvent> events = new ArrayList<>();

        turns.dispatch(new ChannelMessage("web", "sess-a", "hello", Instant.now()), events::add);

        assertEquals(2, events.size(), "Option B emits exactly TokenDelta then Done");
        TokenDelta delta = assertInstanceOf(TokenDelta.class, events.get(0));
        assertEquals("pong", delta.text(), "the fake provider replies 'pong'");
        Done done = assertInstanceOf(Done.class, events.get(1));
        assertEquals("pong", done.finalMessage());
        assertNotNull(done.turnId(), "Done carries the bound CURRENT_TURN id");
    }

    @Test
    void dispatchPersistsTheSessionWithResolvedIdentityAndChannel() {
        turns.dispatch(new ChannelMessage("web", "sess-a", "hello", Instant.now()), e -> { });

        // alice is the configured identity for (web, sess-a); the placeholder default/internal is replaced.
        SessionEntity session = SessionEntity.findById("web:sess-a");
        assertNotNull(session, "the turn created a session keyed channelId:nativeUserId");
        assertEquals("alice", session.identityId);
        assertEquals("web", session.channelId);
    }

    /** Seeds {@code main} pinned to the fake provider + identity {@code alice} for (web, sess-a). */
    public static class ChannelHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-channel-turn-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");
                Path identities = Files.createDirectories(home.resolve("identities"));
                Files.writeString(identities.resolve("alice.json"),
                        "{ \"displayName\": \"Alice\", \"channelAccounts\": { \"web\": \"sess-a\" } }");
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
