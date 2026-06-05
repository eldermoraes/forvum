package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentAgent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/**
 * The {@code AgentToolBelt} filtering contract (ULTRAPLAN section 5.3): the belt is the global
 * {@code ToolRegistry} intersected against the persona's {@code allowedTools} globs — the LLM only ever
 * sees that filtered subset. Seeded {@code main} has {@code allowedTools = ["fs.read", "web.search"]};
 * {@link FakeToolProvider} contributes {@code fs.read, fs.write, web.search, web.get}, so the belt is
 * exactly the two the globs select. Surefire-run (headless library).
 */
@QuarkusTest
@TestProfile(AgentRegistryTestHomeProfile.class)
class AgentToolBeltTest {

    @Inject
    AgentRegistry registry;

    @Test
    void toolsFiltersTheGlobalRegistryByThePersonaGlobs() throws Exception {
        AgentId main = new AgentId("main");
        Agent agent = registry.getOrCreate(main);

        List<String> belt = ScopedValue.where(CurrentAgent.CURRENT_AGENT, main)
                .call(() -> agent.toolBelt().tools().stream().map(ToolSpec::name).toList());

        // The registry's iteration order is not insertion order, so compare as a set.
        assertEquals(Set.of("fs.read", "web.search"), Set.copyOf(belt),
                "the belt is the glob intersection of the global registry");
        assertEquals(2, belt.size(), "no tool appears twice in the belt");
    }
}
