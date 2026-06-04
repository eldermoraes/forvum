package ai.forvum.engine.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import ai.forvum.core.id.AgentId;
import ai.forvum.engine.persistence.PersistenceTestHomeProfile;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The M6 Verify (ULTRAPLAN section 7.1): {@link AgentId}s bound on virtual threads resolve the same
 * {@code @AgentScoped} bean class to distinct instances per agent, the same instance within one agent,
 * and a single shared instance under concurrent same-agent first resolution. Surefire-run (headless
 * library, CLAUDE.md section 4). The persistence test profile keeps PersistenceBootstrap's Flyway run
 * off the developer's real ~/.forvum.
 */
@QuarkusTest
@TestProfile(PersistenceTestHomeProfile.class)
class AgentContextIsolationTest {

    @Inject
    ScopeProbe probe;

    @Test
    void distinctInstancePerAgentAcrossVirtualThreads() throws Exception {
        AgentId a = new AgentId("agent-a");
        AgentId b = new AgentId("agent-b");

        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> fa = exec.submit(
                    () -> ScopedValue.where(CurrentAgent.CURRENT_AGENT, a).call(probe::identity));
            Future<Integer> fb = exec.submit(
                    () -> ScopedValue.where(CurrentAgent.CURRENT_AGENT, b).call(probe::identity));

            assertNotEquals(fa.get(), fb.get(),
                    "two agents must resolve distinct @AgentScoped instances");
        }
    }

    @Test
    void sameInstanceWithinOneAgent() throws Exception {
        AgentId a = new AgentId("agent-a");

        int first = ScopedValue.where(CurrentAgent.CURRENT_AGENT, a).call(probe::identity);
        int second = ScopedValue.where(CurrentAgent.CURRENT_AGENT, a).call(probe::identity);

        assertEquals(first, second, "same agent must resolve the same cached instance");
    }

    @Test
    void concurrentFirstResolutionOfSameAgentYieldsOneSharedInstance() throws Exception {
        AgentId a = new AgentId("agent-concurrent");
        int threads = 8;
        var barrier = new CyclicBarrier(threads);

        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(exec.submit(() -> ScopedValue.where(CurrentAgent.CURRENT_AGENT, a).call(() -> {
                    barrier.await(); // release all bound threads into context.get() simultaneously
                    return probe.identity();
                })));
            }

            int shared = futures.get(0).get();
            for (Future<Integer> f : futures) {
                assertEquals(shared, f.get(),
                        "concurrent first resolution of one agent must create a single shared instance");
            }
        }
    }
}
