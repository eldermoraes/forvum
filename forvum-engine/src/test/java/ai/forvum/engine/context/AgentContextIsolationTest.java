package ai.forvum.engine.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;

import ai.forvum.core.id.AgentId;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The M6 Verify (ULTRAPLAN section 7.1): two {@link AgentId}s bound on two virtual threads
 * concurrently resolve the same {@code @AgentScoped} bean class to distinct instances; the same agent
 * resolves the same cached instance. Surefire-run (headless library, CLAUDE.md section 4).
 */
@QuarkusTest
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
}
