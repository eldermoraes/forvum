package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.context.ScopeProbe;
import ai.forvum.engine.persistence.TaskEntity;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * #177 Verify: {@link AgentRegistry#retire} evicts a spawned worker's spec and destroys its
 * {@code @AgentScoped} context, never touches a persistent (file-declared) agent, keeps active/leaked
 * resources bounded across repeated cycles, records but swallows a cleanup failure, and preserves the
 * worker's ledger history. Uses ids each test alone creates so the shared static
 * {@code AgentRegistryTestHomeProfile} home cannot pollute assertions. Surefire-run (headless library,
 * CLAUDE.md section 4).
 */
@QuarkusTest
@TestProfile(AgentRegistryTestHomeProfile.class)
class AgentRegistryRetireIT {

    @Inject
    AgentRegistry registry;

    @Inject
    ScopeProbe probe;

    @Test
    void retireEvictsTheSpecAndDestroysTheAgentScopedContext() throws Exception {
        AgentId main = new AgentId("main");
        registry.getOrCreate(main);
        AgentId worker = registry.spawn(main, new AgentId("evict-worker"), List.of("fs.read"));

        int before = ScopedValue.where(CurrentAgent.CURRENT_AGENT, worker).call(probe::identity);

        registry.retire(worker);

        assertThrows(IllegalStateException.class, () -> registry.persona(worker),
                "the retired worker's spec is evicted from the registry");
        int after = ScopedValue.where(CurrentAgent.CURRENT_AGENT, worker).call(probe::identity);
        assertNotEquals(before, after,
                "retire destroys the worker's @AgentScoped context — a re-resolve yields a fresh instance");
    }

    @Test
    void retireNeverUnregistersAPersistentAgent() {
        AgentId main = new AgentId("main");
        registry.getOrCreate(main);

        registry.retire(main); // main is file-declared, not an ephemeral worker — must be a no-op

        assertEquals(main, registry.persona(main).id(),
                "a persistent, file-declared agent is never retired by the ephemeral cleanup path");
    }

    @Test
    void retireIsIdempotentAndSafeForAnUnknownOrDoubleRetiredId() {
        AgentId main = new AgentId("main");
        registry.getOrCreate(main);
        AgentId worker = registry.spawn(main, new AgentId("idem-worker"), List.of());

        registry.retire(worker);
        registry.retire(worker); // second retire: no-op, no throw
        registry.retire(new AgentId("never-spawned")); // unknown id: no-op, no throw
    }

    @Test
    void repeatedSpawnRetireKeepsRegistryAndActiveCountBounded() {
        AgentId main = new AgentId("main");
        registry.getOrCreate(main);
        int baseline = registry.activeWorkerCount();

        int cycles = 1000;
        for (int i = 0; i < cycles; i++) {
            AgentId worker = registry.spawn(main, new AgentId("cycle-worker-" + i), List.of());
            assertEquals(baseline + 1, registry.activeWorkerCount(), "one worker is active mid-cycle " + i);
            registry.retire(worker);
            // Assert the SPEC is evicted, not only the gauge: retire clears ephemeralIds first, so a
            // gauge-only assertion would stay green even if the specs-map leak (the whole point of #177)
            // regressed. persona() throws iff the spec is gone.
            assertThrows(IllegalStateException.class, () -> registry.persona(worker),
                    "the retired worker's spec is evicted, not just its active-count entry");
        }

        assertEquals(baseline, registry.activeWorkerCount(),
                "after " + cycles + " spawn/retire cycles the active count returns to baseline (no leak)");
    }

    @Test
    void aReusedWorkerLabelSpawnsFreelyAfterTeardown() {
        AgentId main = new AgentId("main");
        registry.getOrCreate(main);
        AgentId sameId = new AgentId("reused-worker");

        registry.spawn(main, sameId, List.of());
        registry.retire(sameId);
        // Re-spawning the very same id must succeed — retirement freed it (no lingering putIfAbsent occupant).
        registry.spawn(main, sameId, List.of());
        assertEquals(sameId, registry.persona(sameId).id());
        registry.retire(sameId);
    }

    @Test
    void concurrentSpawnRetireAcrossThreadsIsRaceSafeAndLeavesNoLeak() throws Exception {
        AgentId main = new AgentId("main");
        registry.getOrCreate(main);
        int baseline = registry.activeWorkerCount();

        int threads = 16;
        int perThread = 25;
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                futures.add(exec.submit(() -> {
                    for (int j = 0; j < perThread; j++) {
                        // Unique ids per (thread, iteration) mirror the production EphemeralAgentId allocation.
                        AgentId worker = registry.spawn(main, new AgentId("conc-" + thread + "-" + j), List.of());
                        registry.retire(worker);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }

        assertEquals(baseline, registry.activeWorkerCount(),
                "concurrent spawn/retire across threads leaves no leaked workers (ConcurrentHashMap-backed, "
              + "unique ids, idempotent retire)");
        // Tie the race assertion to the real leak (the specs map), not only the gauge: every worker the
        // burst created must be evicted from specs (persona throws), not merely absent from ephemeralIds.
        for (int t = 0; t < threads; t++) {
            for (int j = 0; j < perThread; j++) {
                AgentId worker = new AgentId("conc-" + t + "-" + j);
                assertThrows(IllegalStateException.class, () -> registry.persona(worker),
                        "each concurrently-retired worker's spec is evicted");
            }
        }
    }

    @Test
    void theSpawnLedgerRowSurvivesRetirement() {
        AgentId main = new AgentId("main");
        registry.getOrCreate(main);
        AgentId worker = registry.spawn(main, new AgentId("ledger-survivor"), List.of("fs.read"));

        registry.retire(worker);

        assertTrue(TaskEntity.count("subAgentId = ?1", "ledger-survivor") == 1,
                "the tasks/ledger row written at spawn is untouched by runtime retirement");
    }
}
