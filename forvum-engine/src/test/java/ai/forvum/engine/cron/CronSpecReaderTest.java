package ai.forvum.engine.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ModelRef;
import ai.forvum.core.id.AgentId;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Parses a raw {@code crons/<id>.json} (delivered by the M4 {@code CronReader}) into a typed
 * {@link CronSpec} (M19). The JSON carries the cron expression, target agent, the cron's own model
 * ({@code primary}, distinct from the agent's persona — maintainer decision 2026-06-06), and the prompt.
 */
class CronSpecReaderTest {

    private final CronSpecReader reader = new CronSpecReader();

    private static JsonNode json(String raw) {
        try {
            return new ObjectMapper().readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void parsesAFullCronSpec() {
        CronSpec spec = reader.parse("daily-brief", json(
                "{\"cron\":\"0 * * * * ?\",\"agentId\":\"main\","
              + "\"primary\":\"ollama:llama3.2:1b\",\"prompt\":\"Summarize my day\"}"));

        assertEquals("daily-brief", spec.id());
        assertEquals("0 * * * * ?", spec.cron());
        assertEquals(new AgentId("main"), spec.agentId());
        assertEquals(ModelRef.parse("ollama:llama3.2:1b"), spec.primaryModel());
        assertEquals("Summarize my day", spec.prompt());
    }

    @Test
    void rejectsAMissingRequiredField() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> reader.parse("bad", json(
                "{\"agentId\":\"main\",\"primary\":\"ollama:m\",\"prompt\":\"p\"}")));
        assertTrue(ex.getMessage().contains("cron"), "the message names the missing field, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("bad"), "and the cron id");
    }

    @Test
    void rejectsABlankPrompt() {
        assertThrows(IllegalStateException.class, () -> reader.parse("x", json(
                "{\"cron\":\"0 * * * * ?\",\"agentId\":\"main\",\"primary\":\"ollama:m\",\"prompt\":\"  \"}")));
    }

    @Test
    void rejectsAnUnparseableModelRef() {
        assertThrows(IllegalStateException.class, () -> reader.parse("x", json(
                "{\"cron\":\"0 * * * * ?\",\"agentId\":\"main\",\"primary\":\"no-colon\",\"prompt\":\"p\"}")));
    }
}
