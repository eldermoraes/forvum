package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.MemoryTier;
import ai.forvum.core.ModelRef;
import ai.forvum.core.Persona;
import ai.forvum.core.RetrievalStrategy;
import ai.forvum.core.budget.CostBudget;
import ai.forvum.core.budget.DayWindow;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.graph.CycleSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link AgentSpecReader}: the typed parse of the raw {@code agents/<id>.md} +
 * {@code <id>.json} surface (delivered by the M4 {@code AgentReader}) into a core {@link Persona}.
 * Plain Surefire — no Quarkus boot.
 */
class AgentSpecReaderTest {

    private static JsonNode json(String raw) throws Exception {
        return new ObjectMapper().readTree(raw);
    }

    @Test
    void parsesSystemPromptPrimaryModelAndAllowedTools() throws Exception {
        JsonNode spec = json("""
            { "primaryModel": "ollama:qwen3:1.7b", "allowedTools": ["fs.read", "web.*"] }
            """);

        Persona persona = new AgentSpecReader()
                .parse(new AgentId("main"), "You are the main agent.", spec);

        assertEquals(new AgentId("main"), persona.id());
        assertEquals("You are the main agent.", persona.systemPrompt());
        assertEquals(ModelRef.parse("ollama:qwen3:1.7b"), persona.primaryModel());
        assertEquals(List.of("fs.read", "web.*"), persona.allowedTools());
        assertNull(persona.parent());
        assertNull(persona.costBudget());
        assertNull(persona.toolBudget());
    }

    @Test
    void allowedToolsDefaultsToEmptyWhenAbsent() throws Exception {
        JsonNode spec = json("{ \"primaryModel\": \"ollama:qwen3:1.7b\" }");

        Persona persona = new AgentSpecReader().parse(new AgentId("main"), "persona", spec);

        assertEquals(List.of(), persona.allowedTools());
    }

    @Test
    void readsOptionalParentAndToolBudget() throws Exception {
        JsonNode spec = json("""
            { "primaryModel": "ollama:qwen3:1.7b", "parent": "main", "toolBudget": 20 }
            """);

        Persona persona = new AgentSpecReader().parse(new AgentId("child"), "persona", spec);

        assertEquals(new AgentId("main"), persona.parent());
        assertEquals(20L, persona.toolBudget());
    }

    @Test
    void parsesCostBudgetWithBothCapsExplicitDayWindowAndTimezone() throws Exception {
        JsonNode spec = json("""
            { "primaryModel": "ollama:qwen3:1.7b",
              "costBudget": { "maxUsd": 2.50, "maxTokens": 100000,
                              "window": "day", "timezone": "America/Sao_Paulo" } }
            """);

        Persona persona = new AgentSpecReader().parse(new AgentId("main"), "persona", spec);

        CostBudget budget = persona.costBudget();
        assertNotNull(budget, "a declared costBudget must reach the persona (#169 — no longer deferred)");
        // Value comparison, not equals: Jackson may normalize the decimal scale (2.50 -> 2.5).
        assertEquals(0, new BigDecimal("2.50").compareTo(budget.maxUsd()));
        assertEquals(100_000L, budget.maxTokens());
        DayWindow window = assertInstanceOf(DayWindow.class, budget.window());
        assertEquals(ZoneId.of("America/Sao_Paulo"), window.tz());
    }

    @Test
    void costBudgetWindowAndTimezoneDefaultToDayInTheSystemZone() throws Exception {
        // DR-8 DP-6: file windows are "day"-only, so window may be omitted; an absent timezone
        // resolves to the system zone AT PARSE TIME (the DayWindow ctor contract).
        JsonNode spec = json("""
            { "primaryModel": "ollama:qwen3:1.7b", "costBudget": { "maxUsd": 1 } }
            """);

        CostBudget budget = new AgentSpecReader().parse(new AgentId("main"), "persona", spec).costBudget();

        assertEquals(new BigDecimal("1"), budget.maxUsd());
        assertNull(budget.maxTokens(), "an omitted maxTokens dimension stays opted out");
        assertEquals(new DayWindow(ZoneId.systemDefault()), budget.window());
    }

    @Test
    void costBudgetTokensOnlyAndZeroValuedCapsAreValidBoundaries() throws Exception {
        JsonNode spec = json("""
            { "primaryModel": "ollama:qwen3:1.7b", "costBudget": { "maxTokens": 0 } }
            """);

        CostBudget budget = new AgentSpecReader().parse(new AgentId("main"), "persona", spec).costBudget();

        assertNull(budget.maxUsd());
        assertEquals(0L, budget.maxTokens(), "a zero cap is a valid always-exhausted boundary, not an error");
    }

    @ParameterizedTest
    @MethodSource("invalidCostBudgets")
    void rejectsInvalidCostBudgetsNamingTheFile(String costBudgetJson, String expectedFragment)
            throws Exception {
        JsonNode spec = json("{ \"primaryModel\": \"ollama:qwen3:1.7b\", \"costBudget\": "
                + costBudgetJson + " }");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AgentSpecReader().parse(new AgentId("main"), "persona", spec));

        assertTrue(e.getMessage().contains("agents/main.json"),
                "the diagnostic must name the offending file, got: " + e.getMessage());
        assertTrue(e.getMessage().contains(expectedFragment),
                "expected a '" + expectedFragment + "' diagnostic, got: " + e.getMessage());
    }

    static Stream<Arguments> invalidCostBudgets() {
        return Stream.of(
                Arguments.of("5", "must be a JSON object"),
                Arguments.of("\"cheap\"", "must be a JSON object"),
                Arguments.of("{}", "at least one cap"),
                Arguments.of("{ \"window\": \"day\" }", "at least one cap"),
                Arguments.of("{ \"maxUsd\": -0.01 }", "non-negative"),
                Arguments.of("{ \"maxTokens\": -1 }", "non-negative"),
                Arguments.of("{ \"maxUsd\": \"abc\" }", "costBudget.maxUsd"),
                Arguments.of("{ \"maxUsd\": true }", "costBudget.maxUsd"),
                Arguments.of("{ \"maxTokens\": 1.5 }", "costBudget.maxTokens"),
                Arguments.of("{ \"maxTokens\": \"many\" }", "costBudget.maxTokens"),
                // A config file has no session id, so a "session" window is rejected at parse
                // (§4.3.5.2 Decision 5 / DR-8 DP-6) — and so is any unknown window name.
                Arguments.of("{ \"maxUsd\": 1, \"window\": \"session\" }", "costBudget.window"),
                Arguments.of("{ \"maxUsd\": 1, \"window\": \"week\" }", "costBudget.window"),
                Arguments.of("{ \"maxUsd\": 1, \"timezone\": \"Not/AZone\" }", "costBudget.timezone"));
    }

    @Test
    void rejectsSpecMissingPrimaryModel() throws Exception {
        JsonNode spec = json("{ \"allowedTools\": [] }");

        assertThrows(IllegalStateException.class,
                () -> new AgentSpecReader().parse(new AgentId("main"), "persona", spec));
    }

    @Test
    void rejectsBlankPrimaryModel() throws Exception {
        JsonNode spec = json("{ \"primaryModel\": \"\" }");

        assertThrows(IllegalStateException.class,
                () -> new AgentSpecReader().parse(new AgentId("main"), "persona", spec));
    }

    @Test
    void rejectsNonNumericToolBudget() throws Exception {
        JsonNode spec = json("{ \"primaryModel\": \"ollama:qwen3:1.7b\", \"toolBudget\": \"abc\" }");

        assertThrows(IllegalStateException.class,
                () -> new AgentSpecReader().parse(new AgentId("main"), "persona", spec),
                "a non-numeric toolBudget must be rejected, not silently coerced to 0");
    }

    @Test
    void outputSchemaDefaultsToNullWhenAbsent() throws Exception {
        JsonNode spec = json("{ \"primaryModel\": \"ollama:qwen3:1.7b\" }");

        Persona persona = new AgentSpecReader().parse(new AgentId("main"), "persona", spec);

        assertNull(persona.outputSchema(), "absent outputSchema = free-text (backward compatible)");
    }

    @Test
    void parsesOutputSchemaObjectIntoASerializedString() throws Exception {
        JsonNode spec = json("""
            { "primaryModel": "ollama:qwen3:1.7b",
              "outputSchema": { "type": "object",
                                "required": ["answer"],
                                "properties": { "answer": { "type": "string" } } } }
            """);

        Persona persona = new AgentSpecReader().parse(new AgentId("main"), "persona", spec);

        // The embedded object is re-serialized to compact JSON and must itself re-parse equal to the source.
        JsonNode roundTripped = new ObjectMapper().readTree(persona.outputSchema());
        assertEquals(spec.get("outputSchema"), roundTripped);
    }

    @Test
    void acceptsOutputSchemaSuppliedAsAString() throws Exception {
        JsonNode spec = json("{ \"primaryModel\": \"ollama:qwen3:1.7b\", "
                + "\"outputSchema\": \"{\\\"type\\\":\\\"object\\\"}\" }");

        Persona persona = new AgentSpecReader().parse(new AgentId("main"), "persona", spec);

        assertEquals("{\"type\":\"object\"}", persona.outputSchema());
    }

    @Test
    void rejectsOutputSchemaThatIsNeitherObjectNorString() throws Exception {
        JsonNode spec = json("{ \"primaryModel\": \"ollama:qwen3:1.7b\", \"outputSchema\": 42 }");

        assertThrows(IllegalStateException.class,
                () -> new AgentSpecReader().parse(new AgentId("main"), "persona", spec),
                "a numeric outputSchema is malformed config, not a valid schema");
    }

    // ---- DR-8 fields: fallbackModels / memoryPolicy / roles / identityId / cycle ----

    @Test
    void dr8FieldsDefaultWhenAbsent() throws Exception {
        JsonNode spec = json("{ \"primaryModel\": \"ollama:qwen3:1.7b\" }");

        Persona p = new AgentSpecReader().parse(new AgentId("main"), "persona", spec);

        assertEquals(List.of(), p.fallbackModels());
        assertEquals(MemoryPolicy.defaults(), p.memoryPolicy());
        assertEquals(List.of(), p.roles());
        assertNull(p.identityId());
    }

    @Test
    void parsesFallbackModels() throws Exception {
        JsonNode spec = json("""
            { "primaryModel": "ollama:qwen3:1.7b",
              "fallbackModels": ["openai:gpt-4.1-mini", "anthropic:claude-3-5"] }
            """);

        Persona p = new AgentSpecReader().parse(new AgentId("main"), "persona", spec);

        assertEquals(List.of(ModelRef.parse("openai:gpt-4.1-mini"), ModelRef.parse("anthropic:claude-3-5")),
                p.fallbackModels());
    }

    @Test
    void rejectsFallbackModelsThatIsNotAnArray() throws Exception {
        JsonNode spec = json("{ \"primaryModel\": \"ollama:qwen3:1.7b\", \"fallbackModels\": \"x\" }");

        assertThrows(IllegalStateException.class,
                () -> new AgentSpecReader().parse(new AgentId("main"), "persona", spec));
    }

    @Test
    void parsesRolesAndIdentityId() throws Exception {
        JsonNode spec = json("""
            { "primaryModel": "ollama:qwen3:1.7b", "roles": ["research-readonly"], "identityId": "default" }
            """);

        Persona p = new AgentSpecReader().parse(new AgentId("main"), "persona", spec);

        assertEquals(List.of("research-readonly"), p.roles());
        assertEquals("default", p.identityId());
    }

    @Test
    void memoryPolicyBlockDefaultsEachOmittedFieldFromDefaults() throws Exception {
        // Only topK supplied — every other field defaults from MemoryPolicy.defaults() (DR-8 DP-5).
        JsonNode spec = json("""
            { "primaryModel": "ollama:qwen3:1.7b", "memoryPolicy": { "topK": 4 } }
            """);

        MemoryPolicy policy =
                new AgentSpecReader().parse(new AgentId("main"), "persona", spec).memoryPolicy();
        MemoryPolicy d = MemoryPolicy.defaults();

        assertEquals(4, policy.topK());
        assertEquals(d.strategy(), policy.strategy());
        assertEquals(d.tiers(), policy.tiers());
        assertEquals(d.minScore(), policy.minScore());
        assertEquals(d.compressThresholdChars(), policy.compressThresholdChars());
    }

    @Test
    void memoryPolicyEnumsParseCaseInsensitively() throws Exception {
        JsonNode spec = json("""
            { "primaryModel": "ollama:qwen3:1.7b",
              "memoryPolicy": { "strategy": "metadata", "tiers": ["messages", "semantic"] } }
            """);

        MemoryPolicy policy =
                new AgentSpecReader().parse(new AgentId("main"), "persona", spec).memoryPolicy();

        assertEquals(RetrievalStrategy.METADATA, policy.strategy());
        assertEquals(EnumSet.of(MemoryTier.MESSAGES, MemoryTier.SEMANTIC), policy.tiers());
    }

    @Test
    void rejectsAnUnknownMemoryStrategy() throws Exception {
        JsonNode spec = json("{ \"primaryModel\": \"ollama:qwen3:1.7b\", "
                + "\"memoryPolicy\": { \"strategy\": \"bogus\" } }");

        assertThrows(IllegalStateException.class,
                () -> new AgentSpecReader().parse(new AgentId("main"), "persona", spec));
    }

    @Test
    void rejectsAnUnknownMemoryTier() throws Exception {
        JsonNode spec = json("{ \"primaryModel\": \"ollama:qwen3:1.7b\", "
                + "\"memoryPolicy\": { \"tiers\": [\"bogus\"] } }");

        assertThrows(IllegalStateException.class,
                () -> new AgentSpecReader().parse(new AgentId("main"), "persona", spec));
    }

    @Test
    void rejectsANonNumericTopK() throws Exception {
        JsonNode spec = json("{ \"primaryModel\": \"ollama:qwen3:1.7b\", "
                + "\"memoryPolicy\": { \"topK\": \"abc\" } }");

        assertThrows(IllegalStateException.class,
                () -> new AgentSpecReader().parse(new AgentId("main"), "persona", spec),
                "a non-numeric topK must be rejected, not silently coerced to 0");
    }

    @Test
    void parseSpecDefaultsCycleToNullWhenAbsent() throws Exception {
        JsonNode spec = json("{ \"primaryModel\": \"ollama:qwen3:1.7b\" }");

        AgentSpec s = new AgentSpecReader().parseSpec(new AgentId("main"), "persona", spec);

        assertNull(s.cycle(), "no cycle block = the standard supervisor graph");
        assertEquals(new AgentId("main"), s.persona().id());
    }

    @Test
    void parseSpecReadsTheCycleBlock() throws Exception {
        JsonNode spec = json("""
            { "primaryModel": "ollama:qwen3:1.7b",
              "cycle": { "steps": ["reflect", "critique", "revise"], "maxRounds": 2, "stopSentinel": "DONE" } }
            """);

        CycleSpec c = new AgentSpecReader().parseSpec(new AgentId("main"), "persona", spec).cycle();

        assertNotNull(c);
        assertEquals(List.of("reflect", "critique", "revise"), c.steps());
        assertEquals(2, c.maxRounds());
        assertEquals("DONE", c.stopSentinel());
    }

    @Test
    void parseSpecDefaultsCycleMaxRoundsToThree() throws Exception {
        JsonNode spec = json("{ \"primaryModel\": \"ollama:qwen3:1.7b\", "
                + "\"cycle\": { \"steps\": [\"reflect\"] } }");

        CycleSpec c = new AgentSpecReader().parseSpec(new AgentId("main"), "persona", spec).cycle();

        assertEquals(3, c.maxRounds(), "absent cycle.maxRounds defaults to 3 (DR-8 DP-7)");
        assertNull(c.stopSentinel());
    }

    @Test
    void rejectsACycleWithoutSteps() throws Exception {
        JsonNode spec = json("{ \"primaryModel\": \"ollama:qwen3:1.7b\", \"cycle\": { \"maxRounds\": 2 } }");

        assertThrows(IllegalStateException.class,
                () -> new AgentSpecReader().parseSpec(new AgentId("main"), "persona", spec));
    }
}
