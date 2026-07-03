package ai.forvum.engine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.engine.memory.FactExtractor.ExtractedFact;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The pure JSON-parsing half of the LLM fact extractor (#175): tolerant tree-walk of the small model's
 * output into {@code (key, value)} facts, robust to markdown fences, surrounding prose, empty arrays, and
 * malformed JSON (which must degrade to no facts, never throw). No model call, no CDI.
 */
class FactExtractorTest {

    @Test
    void parsesAJsonArrayOfKeyValueFacts() {
        List<ExtractedFact> facts = FactExtractor.parse(
                "[{\"key\":\"user.name\",\"value\":\"Elder\"},{\"key\":\"user.city\",\"value\":\"Berlin\"}]");

        assertEquals(2, facts.size());
        assertEquals("user.name", facts.get(0).key());
        assertEquals("Elder", facts.get(0).value());
        assertEquals("user.city", facts.get(1).key());
        assertEquals("Berlin", facts.get(1).value());
    }

    @Test
    void toleratesMarkdownFencesAndSurroundingProse() {
        List<ExtractedFact> facts = FactExtractor.parse(
                "Sure! Facts:\n```json\n[{\"key\":\"pet\",\"value\":\"cat\"}]\n```\nHope that helps.");

        assertEquals(1, facts.size());
        assertEquals("pet", facts.get(0).key());
        assertEquals("cat", facts.get(0).value());
    }

    @Test
    void anEmptyArrayOrNoArrayYieldsNoFacts() {
        assertTrue(FactExtractor.parse("[]").isEmpty());
        assertTrue(FactExtractor.parse("No durable facts here.").isEmpty(), "no JSON array -> no facts");
    }

    @Test
    void malformedJsonOrNullYieldsNoFactsRatherThanThrowing() {
        assertTrue(FactExtractor.parse("[{\"key\": ]").isEmpty());
        assertTrue(FactExtractor.parse(null).isEmpty());
    }

    @Test
    void extractsTheObjectArrayEvenWhenProseHasStrayBrackets() {
        List<ExtractedFact> facts = FactExtractor.parse(
                "I noted [the city] as a fact: [{\"key\":\"user.city\",\"value\":\"Rio\"}] — all done.");

        assertEquals(1, facts.size(), "a stray '[...]' in prose must not corrupt the object-array slice");
        assertEquals("user.city", facts.get(0).key());
        assertEquals("Rio", facts.get(0).value());
    }

    @Test
    void entriesMissingKeyOrValueAreSkipped() {
        List<ExtractedFact> facts = FactExtractor.parse(
                "[{\"key\":\"a\",\"value\":\"1\"},{\"key\":\"\",\"value\":\"2\"},"
                        + "{\"value\":\"3\"},{\"key\":\"b\",\"value\":\"2\"}]");

        assertEquals(2, facts.size(), "blank-key and key-less entries are dropped");
        assertEquals("a", facts.get(0).key());
        assertEquals("b", facts.get(1).key());
    }
}
