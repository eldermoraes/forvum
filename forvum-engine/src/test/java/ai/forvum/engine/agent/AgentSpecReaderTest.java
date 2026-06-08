package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.core.ModelRef;
import ai.forvum.core.Persona;
import ai.forvum.core.id.AgentId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

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
}
