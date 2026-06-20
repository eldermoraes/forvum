package ai.forvum.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/** {@link EvalSuiteReader} JSON binding + validation (P3-10 #58), mirroring CronSpecReaderTest. */
class EvalSuiteReaderTest {

    private final EvalSuiteReader reader = new EvalSuiteReader();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void parsesAFullSuiteWithPerScenarioMatch() {
        EvalSuite suite = reader.parse("smoke", json("""
                {
                  "agent": "main",
                  "floor": 0.8,
                  "match": "contains",
                  "scenarios": [
                    { "id": "greet", "prompt": "say hi", "expect": "pong" },
                    { "id": "code", "prompt": "give a code", "expect": "\\\\d+", "match": "regex" }
                  ]
                }
                """));
        assertEquals("smoke", suite.name());
        assertEquals("main", suite.agentId());
        assertEquals(0.8, suite.floor(), 1e-9);
        assertNull(suite.judge(), "no judge field → the offline matcher default");
        assertEquals(2, suite.scenarios().size());
        assertEquals(MatchMode.CONTAINS, suite.scenarios().get(0).match(), "scenario inherits the suite match");
        assertEquals(MatchMode.REGEX, suite.scenarios().get(1).match(), "scenario overrides the suite match");
    }

    @Test
    void defaultsTheMatchToContainsWhenAbsent() {
        EvalSuite suite = reader.parse("d", json("""
                { "agent": "main", "floor": 1.0,
                  "scenarios": [ { "id": "a", "prompt": "p", "expect": "x" } ] }
                """));
        assertEquals(MatchMode.CONTAINS, suite.scenarios().get(0).match());
    }

    @Test
    void readsAnOptionalLlmJudgeRef() {
        EvalSuite suite = reader.parse("j", json("""
                { "agent": "main", "floor": 0.5, "judge": "llm:ollama:qwen2.5:0.5b",
                  "scenarios": [ { "id": "a", "prompt": "p", "expect": "x" } ] }
                """));
        assertEquals("llm:ollama:qwen2.5:0.5b", suite.judge());
    }

    @Test
    void rejectsAMissingAgentWithTheFileHint() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> reader.parse("bad", json("""
                { "floor": 0.5, "scenarios": [ { "id": "a", "prompt": "p", "expect": "x" } ] }
                """)));
        assertTrue(ex.getMessage().contains("agent"), ex.getMessage());
        assertTrue(ex.getMessage().contains("eval/bad.json"), ex.getMessage());
    }

    @Test
    void rejectsAMissingFloor() {
        assertThrows(IllegalStateException.class, () -> reader.parse("bad", json("""
                { "agent": "main", "scenarios": [ { "id": "a", "prompt": "p", "expect": "x" } ] }
                """)));
    }

    @Test
    void rejectsAnOutOfRangeFloor() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> reader.parse("bad", json("""
                { "agent": "main", "floor": 1.5,
                  "scenarios": [ { "id": "a", "prompt": "p", "expect": "x" } ] }
                """)));
        assertTrue(ex.getMessage().contains("eval/bad.json"), ex.getMessage());
    }

    @Test
    void rejectsAnEmptyScenarioArray() {
        assertThrows(IllegalStateException.class, () -> reader.parse("bad", json("""
                { "agent": "main", "floor": 0.5, "scenarios": [] }
                """)));
    }

    @Test
    void rejectsAScenarioMissingExpect() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> reader.parse("bad", json("""
                { "agent": "main", "floor": 0.5, "scenarios": [ { "id": "a", "prompt": "p" } ] }
                """)));
        assertTrue(ex.getMessage().contains("expect"), ex.getMessage());
    }

    @Test
    void rejectsAnInvalidScenarioMatch() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> reader.parse("bad", json("""
                { "agent": "main", "floor": 0.5,
                  "scenarios": [ { "id": "a", "prompt": "p", "expect": "x", "match": "fuzzy" } ] }
                """)));
        assertTrue(ex.getMessage().contains("eval/bad.json"), ex.getMessage());
    }

    @Test
    void rejectsANonObjectSpec() {
        assertThrows(IllegalStateException.class, () -> reader.parse("bad", json("[]")));
    }

    @Test
    void rejectsANonObjectScenarioEntry() {
        assertThrows(IllegalStateException.class, () -> reader.parse("bad", json("""
                { "agent": "main", "floor": 0.5, "scenarios": [ "oops" ] }
                """)));
    }
}
