package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import ai.forvum.core.ModelRef;
import ai.forvum.core.Persona;
import ai.forvum.core.budget.CostBudget;
import ai.forvum.core.budget.DayWindow;
import ai.forvum.core.budget.SessionWindow;
import ai.forvum.core.budget.SpawnConfigurationException;
import ai.forvum.core.RetrievalStrategy;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.config.ChangeType;
import ai.forvum.engine.config.ConfigurationChangedEvent;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.graph.CycleSpec;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
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
    void reloadPublishesAValidatedGenerationAtomicallyWithoutGetOrCreate() throws Exception {
        Path agents = AgentRegistryTestHomeProfile.HOME.resolve("agents");
        Path md = agents.resolve("atomic.md");
        Path json = agents.resolve("atomic.json");
        try {
            Files.writeString(md, "persona");
            Files.writeString(json, "{ \"primaryModel\": \"ollama:qwen3:1.7b\", \"allowedTools\": [] }");
            AgentId id = new AgentId("atomic");
            registry.getOrCreate(id);
            long gen0 = registry.generation(id);

            Files.writeString(json, "{ \"primaryModel\": \"fake:v2\", \"allowedTools\": [] }");
            configChanged.fire(new ConfigurationChangedEvent(
                    Path.of("agents", "atomic.json"), ChangeType.MODIFIED));

            // The publish is immediate — NO getOrCreate — and the generation advanced.
            assertEquals(ModelRef.parse("fake:v2"), registry.persona(id).primaryModel(),
                    "a reload publishes atomically for new turns with no getOrCreate");
            assertTrue(registry.generation(id) > gen0, "a successful reload advances the generation");
        } finally {
            Files.deleteIfExists(md);
            Files.deleteIfExists(json);
            configChanged.fire(new ConfigurationChangedEvent(
                    Path.of("agents", "atomic.json"), ChangeType.DELETED));
        }
    }

    @Test
    void anInvalidReloadKeepsTheLastKnownGoodAndDoesNotWiden() throws Exception {
        Path agents = AgentRegistryTestHomeProfile.HOME.resolve("agents");
        Path md = agents.resolve("keepsgood.md");
        Path json = agents.resolve("keepsgood.json");
        try {
            Files.writeString(md, "persona");
            Files.writeString(json,
                    "{ \"primaryModel\": \"ollama:qwen3:1.7b\", \"allowedTools\": [\"fs.read\"] }");
            AgentId id = new AgentId("keepsgood");
            registry.getOrCreate(id);
            long failuresBefore = registry.reloadFailureCount();

            // Malformed / half-written JSON (truncated) — a mid-write read.
            Files.writeString(json,
                    "{ \"primaryModel\": \"fake:v2\", \"allowedTools\": [\"fs.read\", \"web.");
            configChanged.fire(new ConfigurationChangedEvent(
                    Path.of("agents", "keepsgood.json"), ChangeType.MODIFIED));

            assertEquals(ModelRef.parse("ollama:qwen3:1.7b"), registry.persona(id).primaryModel(),
                    "an invalid reload must retain the last known-good spec");
            assertEquals(List.of("fs.read"), registry.persona(id).allowedTools(),
                    "an invalid reload must NOT widen capabilities");
            assertTrue(registry.reloadFailureCount() > failuresBefore, "the failure is counted");
        } finally {
            Files.deleteIfExists(md);
            Files.deleteIfExists(json);
            configChanged.fire(new ConfigurationChangedEvent(
                    Path.of("agents", "keepsgood.json"), ChangeType.DELETED));
        }
    }

    @Test
    void deletingAnAgentFileUnregistersItSoANewLeaseFails() throws Exception {
        Path agents = AgentRegistryTestHomeProfile.HOME.resolve("agents");
        Path md = agents.resolve("deletable.md");
        Path json = agents.resolve("deletable.json");
        Files.writeString(md, "persona");
        Files.writeString(json, "{ \"primaryModel\": \"ollama:qwen3:1.7b\", \"allowedTools\": [] }");
        AgentId id = new AgentId("deletable");
        registry.getOrCreate(id);
        assertTrue(registry.generation(id) >= 0, "the agent is registered before deletion");

        Files.deleteIfExists(json);
        configChanged.fire(new ConfigurationChangedEvent(
                Path.of("agents", "deletable.json"), ChangeType.DELETED));

        assertEquals(-1L, registry.generation(id), "a deleted agent is unregistered");
        assertThrows(IllegalStateException.class, () -> registry.lease(id),
                "a new turn leasing a deleted agent must fail (rejected new-turn)");
        Files.deleteIfExists(md);
    }

    @Test
    void aBoundLeaseFreezesItsGenerationAgainstAConcurrentReload() throws Exception {
        Path agents = AgentRegistryTestHomeProfile.HOME.resolve("agents");
        Path md = agents.resolve("leased.md");
        Path json = agents.resolve("leased.json");
        try {
            Files.writeString(md, "persona");
            Files.writeString(json, "{ \"primaryModel\": \"ollama:qwen3:1.7b\", \"allowedTools\": [] }");
            AgentId id = new AgentId("leased");
            registry.getOrCreate(id);
            LiveAgent leased = registry.lease(id);

            // A reload of the file lands AFTER the lease is taken (a concurrent operator edit mid-turn).
            Files.writeString(json, "{ \"primaryModel\": \"fake:new\", \"allowedTools\": [] }");
            configChanged.fire(new ConfigurationChangedEvent(
                    Path.of("agents", "leased.json"), ChangeType.MODIFIED));

            // Inside the lease binding, the turn still observes the generation it leased — never the reload.
            ModelRef insideLease = ScopedValue.where(AgentRegistry.CURRENT_AGENT_SPEC, leased)
                    .call(() -> registry.persona(id).primaryModel());
            assertEquals(ModelRef.parse("ollama:qwen3:1.7b"), insideLease,
                    "an in-flight turn must observe its leased generation, not a concurrent reload");
        } finally {
            Files.deleteIfExists(md);
            Files.deleteIfExists(json);
            configChanged.fire(new ConfigurationChangedEvent(
                    Path.of("agents", "leased.json"), ChangeType.DELETED));
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

    // ---- DR-8 composition: spec(id), the non-default fields, and spawn inheritance ----

    @Test
    void specReadsTheDeclaredCycle() {
        AgentId policied = new AgentId("policied");
        registry.getOrCreate(policied);

        CycleSpec cycle = registry.spec(policied).cycle();

        assertNotNull(cycle, "the agent's declared cycle must be reachable via spec(id)");
        assertEquals(List.of("reflect", "critique", "revise"), cycle.steps());
        assertEquals(2, cycle.maxRounds());
        assertEquals("DONE", cycle.stopSentinel());
    }

    @Test
    void personaCarriesTheNonDefaultDR8Fields() {
        AgentId policied = new AgentId("policied");
        registry.getOrCreate(policied);

        Persona p = registry.persona(policied);

        assertEquals(List.of(ModelRef.parse("openai:gpt-4.1-mini")), p.fallbackModels());
        assertEquals(List.of("research-readonly"), p.roles());
        assertEquals("default", p.identityId());
        assertEquals(RetrievalStrategy.METADATA, p.memoryPolicy().strategy());
        assertEquals(4, p.memoryPolicy().topK());
    }

    @Test
    void spawnInheritsDR8FieldsVerbatimButNotTheCycle() {
        AgentId policied = new AgentId("policied");
        registry.getOrCreate(policied);
        Persona parent = registry.persona(policied);

        AgentId child = registry.spawn(policied, new AgentId("policied-child"), List.of("fs.read"));
        Persona childPersona = registry.persona(child);

        // The 4 DR-8 fields inherit verbatim — non-default values, so this proves real inheritance, not a
        // default coincidence (the [M19] override-only-if-distinct discipline).
        assertEquals(parent.fallbackModels(), childPersona.fallbackModels());
        assertEquals(parent.memoryPolicy(), childPersona.memoryPolicy());
        assertEquals(parent.roles(), childPersona.roles());
        assertEquals(parent.identityId(), childPersona.identityId());
        // The declared cycle is NOT inherited — a worker runs a single direct generation (M18).
        assertNull(registry.spec(child).cycle());
    }

    // ---- #169 / Decision 10: spawn-time CostBudget override + the SessionWindow guard ----

    @Test
    void spawnAcceptsAnExplicitCostBudgetOverrideAndInheritsItDownTheTree() {
        AgentId main = new AgentId("main");
        registry.getOrCreate(main);
        CostBudget override = new CostBudget(null, 500L, new DayWindow(ZoneId.of("UTC")));

        AgentId child = registry.spawn(main, new AgentId("capped-child"), List.of("fs.read"), override);

        assertEquals(override, registry.persona(child).costBudget(),
                "an explicit override replaces the parent's (null) budget");

        // A DayWindow budget inherits verbatim into a grandchild: the record is immutable and the meter
        // scopes the day aggregation per agent, so the grandchild's spend is tracked independently.
        AgentId grandchild = registry.spawn(child, new AgentId("capped-grandchild"), List.of());
        assertEquals(override, registry.persona(grandchild).costBudget());
    }

    @Test
    void spawnRejectsInheritingASessionWindowBudgetWithoutAnOverride() {
        // A SessionWindow filters by the PARENT's (sessionId, agentId) pair — inherited verbatim, the
        // child's own calls would be invisible to its budget SUM (effectively uncapped). The guard
        // surfaces the misconfiguration at spawn time (Decision 10, dormant since M7 — activated by #169).
        AgentId main = new AgentId("main");
        registry.getOrCreate(main);
        CostBudget sessionScoped = new CostBudget(null, 500L, new SessionWindow("sess-1", "session-parent"));
        AgentId parent = registry.spawn(main, new AgentId("session-parent"), List.of(), sessionScoped);

        SpawnConfigurationException e = assertThrows(SpawnConfigurationException.class,
                () -> registry.spawn(parent, new AgentId("orphaned-budget-child"), List.of()),
                "inheriting a SessionWindow parent budget without an override must be rejected");

        assertEquals("session-parent", e.parentAgentId());
        assertEquals("orphaned-budget-child", e.childAgentId());

        // An explicit override IS the documented remedy — the same spawn succeeds with one.
        AgentId child = registry.spawn(parent, new AgentId("overridden-budget-child"), List.of(),
                new CostBudget(null, 100L, new DayWindow(ZoneId.of("UTC"))));
        assertEquals(100L, registry.persona(child).costBudget().maxTokens());
    }
}
