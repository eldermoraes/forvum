package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import ai.forvum.core.ModelRef;
import ai.forvum.core.Persona;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentAgent;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The M7 Verify (ULTRAPLAN section 7.1): a file-seeded {@code main} agent resolves through
 * {@link AgentRegistry#getOrCreate} to one cached {@code @AgentScoped} instance per agent, with a
 * persona + tool belt drawn from {@code agents/main.json}; {@link AgentRegistry#spawn} yields a
 * distinct child id with a narrower tool belt. Surefire-run (headless library, CLAUDE.md section 4).
 */
@QuarkusTest
@TestProfile(AgentRegistryTestHomeProfile.class)
class AgentRegistryTest {

    @Inject
    AgentRegistry registry;

    @Test
    void getOrCreateLoadsSpecFromFilesAndCachesOneInstancePerAgent() throws Exception {
        AgentId main = new AgentId("main");
        Agent agent = registry.getOrCreate(main);

        int first = ScopedValue.where(CurrentAgent.CURRENT_AGENT, main).call(agent::identity);
        int second = ScopedValue.where(CurrentAgent.CURRENT_AGENT, main).call(agent::identity);
        assertEquals(first, second, "the same agent must resolve one cached @AgentScoped instance");

        Persona persona = ScopedValue.where(CurrentAgent.CURRENT_AGENT, main).call(agent::persona);
        assertEquals(main, persona.id());
        assertEquals("You are the main agent.", persona.systemPrompt());
        assertEquals(ModelRef.parse("ollama:qwen3:1.7b"), persona.primaryModel());

        List<String> globs = ScopedValue.where(CurrentAgent.CURRENT_AGENT, main)
                .call(() -> agent.toolBelt().globs());
        assertEquals(List.of("fs.read", "web.search"), globs);
    }

    @Test
    void spawnCreatesADistinctChildWithANarrowerToolBelt() throws Exception {
        AgentId main = new AgentId("main");
        registry.getOrCreate(main);

        AgentId child = registry.spawn(main, new AgentId("researcher"), List.of("fs.read"));
        assertNotEquals(main, child);

        Agent childAgent = registry.getOrCreate(child);
        List<String> childGlobs = ScopedValue.where(CurrentAgent.CURRENT_AGENT, child)
                .call(() -> childAgent.toolBelt().globs());
        assertEquals(List.of("fs.read"), childGlobs);

        List<String> parentGlobs = List.of("fs.read", "web.search");
        assertTrue(parentGlobs.containsAll(childGlobs), "child tool belt must be a subset of the parent's");
    }

    @Test
    void spawnRejectsAToolBeltWiderThanTheParent() {
        AgentId main = new AgentId("main");
        registry.getOrCreate(main);

        assertThrows(IllegalStateException.class,
                () -> registry.spawn(main, new AgentId("rogue"), List.of("shell.exec")));
    }
}
