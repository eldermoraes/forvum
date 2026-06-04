package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import ai.forvum.core.ModelRef;
import ai.forvum.core.Persona;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.config.ChangeType;
import ai.forvum.engine.config.ConfigurationChangedEvent;
import ai.forvum.engine.context.CurrentAgent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
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

    @Inject
    Event<ConfigurationChangedEvent> configChanged;

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

    @Test
    void hotReloadEvictsAChangedAgentSoTheNextGetOrCreateRereadsIt() throws Exception {
        Path agents = AgentRegistryTestHomeProfile.HOME.resolve("agents");
        Path md = agents.resolve("reloadable.md");
        Path json = agents.resolve("reloadable.json");
        try {
            Files.writeString(md, "persona");
            Files.writeString(json, "{ \"primaryModel\": \"ollama:qwen3:1.7b\", \"allowedTools\": [] }");

            AgentId id = new AgentId("reloadable");
            registry.getOrCreate(id);
            assertEquals(ModelRef.parse("ollama:qwen3:1.7b"), registry.persona(id).primaryModel());

            Files.writeString(json, "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");
            configChanged.fire(new ConfigurationChangedEvent(
                    Path.of("agents", "reloadable.json"), ChangeType.MODIFIED));

            registry.getOrCreate(id);
            assertEquals(ModelRef.parse("fake:test-model"), registry.persona(id).primaryModel(),
                    "a changed agent file must be re-read on the next getOrCreate");
        } finally {
            // Keep the shared static seed home self-contained: drop this test's residue.
            Files.deleteIfExists(md);
            Files.deleteIfExists(json);
            configChanged.fire(new ConfigurationChangedEvent(
                    Path.of("agents", "reloadable.json"), ChangeType.DELETED));
        }
    }

    @Test
    void spawnRejectsAChildIdEqualToTheParent() {
        AgentId main = new AgentId("main");
        registry.getOrCreate(main);

        assertThrows(IllegalStateException.class,
                () -> registry.spawn(main, main, List.of("fs.read")),
                "a child must be a distinct agent id, never the parent itself");
    }

    @Test
    void spawnRejectsCollisionWithAnAlreadyRegisteredId() {
        AgentId main = new AgentId("main");
        registry.getOrCreate(main);
        registry.getOrCreate(new AgentId("faker"));

        assertThrows(IllegalStateException.class,
                () -> registry.spawn(main, new AgentId("faker"), List.of()),
                "spawn must not silently overwrite an already-registered agent");
    }

    @Test
    void getOrCreateThrowsWhenTheAgentFilesAreAbsent() {
        assertThrows(IllegalStateException.class,
                () -> registry.getOrCreate(new AgentId("ghost")));
    }

    @Test
    void personaThrowsForAnUnregisteredAgent() {
        assertThrows(IllegalStateException.class,
                () -> registry.persona(new AgentId("never-loaded")));
    }
}
