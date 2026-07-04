package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.config.ChangeType;
import ai.forvum.engine.config.ConfigurationChangedEvent;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.enterprise.event.Event;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@code TurnService} binds the per-turn agent-spec lease (#178): a turn driven end-to-end through
 * {@link TurnService#dispatch} runs the WHOLE turn under a bound {@link AgentRegistry#CURRENT_AGENT_SPEC},
 * so every config read for the turn resolves the same frozen generation and an in-flight turn survives a
 * concurrent reload/delete. Proven by {@link LeaseProbeModelProvider}, which records the lease state at
 * chat time and can pause the turn there.
 */
@QuarkusTest
@TestProfile(TurnServiceLeaseIT.LeaseHomeProfile.class)
class TurnServiceLeaseIT {

    @Inject
    TurnService turns;

    @Inject
    AgentRegistry registry;

    @Inject
    Event<ConfigurationChangedEvent> configChanged;

    @Test
    void dispatchRunsTheWholeTurnUnderABoundLease() {
        LeaseProbeModelProvider.resetProbe();
        AgentId main = new AgentId("main");
        registry.getOrCreate(main);
        long gen = registry.generation(main);

        turns.dispatch(new ChannelMessage("web", "probe-user", "hi", Instant.now()), e -> { });

        assertTrue(LeaseProbeModelProvider.leaseBoundDuringTurn,
                "TurnService must bind CURRENT_AGENT_SPEC around the whole turn (#178)");
        assertEquals(gen, LeaseProbeModelProvider.leasedGeneration,
                "the turn runs on the generation it leased at entry");
    }

    @Test
    void anInFlightTurnCompletesWhenItsAgentIsDeletedMidTurn() throws Exception {
        LeaseProbeModelProvider.resetProbe();
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LeaseProbeModelProvider.reachedModel = reached;
        LeaseProbeModelProvider.releaseModel = release;

        List<AgentEvent> events = new ArrayList<>();
        Thread turn = Thread.ofVirtual().start(() ->
                turns.dispatch(new ChannelMessage("web", "probe-user", "hi", Instant.now()), events::add));

        assertTrue(reached.await(10, TimeUnit.SECONDS), "the turn must reach the model");
        // Delete the agent MID-TURN: unregister its spec + destroy its @AgentScoped context. The in-flight
        // turn holds an immutable leased snapshot, so this must not disrupt it. (The .json file is left on
        // disk; the event alone drives the registry teardown, and the seed home stays intact for other tests.)
        configChanged.fire(new ConfigurationChangedEvent(
                Path.of("agents", "main.json"), ChangeType.DELETED));
        release.countDown();
        turn.join(10_000);

        assertFalse(events.isEmpty(), "the in-flight turn produced events");
        assertInstanceOf(Done.class, events.get(events.size() - 1),
                "the in-flight turn completed normally on its leased generation despite the mid-turn delete");
    }

    /** Seeds {@code main} pinned to the {@code leaseprobe} provider (belt empty — the turn takes no tool path). */
    public static class LeaseHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-lease-it-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"leaseprobe:test\", \"allowedTools\": [] }");
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
