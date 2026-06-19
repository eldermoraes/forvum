package ai.forvum.engine.graph;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Value-side {@link OutputSchemaValidator#validate} coverage (#124). Proves the formal-JSON-Schema engine
 * the validator now delegates to enforces the FULL draft-2020-12 surface — not just the original
 * v0.5-parity subset (root {@code type}, {@code required}, primitive property {@code type}) — covering
 * {@code enum}, nested object/array schemas, numeric bounds, string length, and {@code pattern}. The
 * supervisor-level wiring (turn aborts, schema named in the {@code SupervisorGraphException}) is still
 * exercised by {@code SupervisorGraphTest}; this test pins the validator's own behavior directly.
 */
class OutputSchemaValidatorTest {

    private final OutputSchemaValidator validator = new OutputSchemaValidator(new ObjectMapper());

    // --- baseline (preserved) v0.5 subset -----------------------------------------------------------

    @Test
    void aReplyMatchingTheSchemaIsReturnedAsAJsonNode() {
        JsonNode node = validator.validate(
                "{\"type\":\"object\",\"required\":[\"answer\"],"
                        + "\"properties\":{\"answer\":{\"type\":\"string\"}}}",
                "{\"answer\":\"42\"}");
        assertEquals("42", node.get("answer").asText());
    }

    @Test
    void aNonJsonReplyIsRejectedNamingItIsNotValidJson() {
        OutputSchemaException e = assertThrows(OutputSchemaException.class,
                () -> validator.validate("{\"type\":\"object\"}", "not json at all"));
        assertTrue(e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("not valid json"),
                "message: " + e.getMessage());
    }

    @Test
    void anEmptyReplyIsRejected() {
        OutputSchemaException e = assertThrows(OutputSchemaException.class,
                () -> validator.validate("{\"type\":\"object\"}", ""));
        assertTrue(e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("empty"),
                "message: " + e.getMessage());
    }

    @Test
    void aMissingRequiredFieldIsRejectedNamingTheField() {
        OutputSchemaException e = assertThrows(OutputSchemaException.class,
                () -> validator.validate(
                        "{\"type\":\"object\",\"required\":[\"answer\"]}",
                        "{\"other\":1}"));
        assertTrue(e.getMessage().contains("answer"), "message: " + e.getMessage());
    }

    @Test
    void aWrongPrimitiveTypeIsRejectedNamingTheField() {
        OutputSchemaException e = assertThrows(OutputSchemaException.class,
                () -> validator.validate(
                        "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}}}",
                        "{\"answer\":42}"));
        assertTrue(e.getMessage().contains("answer") && e.getMessage().contains("string"),
                "message: " + e.getMessage());
    }

    // --- fuller draft coverage (the #124 upgrade) ---------------------------------------------------

    @Test
    void enumValuesAreEnforced() {
        String schema = "{\"type\":\"object\",\"properties\":{\"status\":{\"enum\":[\"ok\",\"err\"]}}}";
        assertDoesNotThrow(() -> validator.validate(schema, "{\"status\":\"ok\"}"));
        OutputSchemaException e = assertThrows(OutputSchemaException.class,
                () -> validator.validate(schema, "{\"status\":\"maybe\"}"));
        assertTrue(e.getMessage().contains("status"), "message: " + e.getMessage());
    }

    @Test
    void nestedObjectSchemasAreEnforced() {
        String schema = "{\"type\":\"object\",\"properties\":{\"meta\":{\"type\":\"object\","
                + "\"required\":[\"id\"],\"properties\":{\"id\":{\"type\":\"integer\"}}}}}";
        assertDoesNotThrow(() -> validator.validate(schema, "{\"meta\":{\"id\":7}}"));
        OutputSchemaException e = assertThrows(OutputSchemaException.class,
                () -> validator.validate(schema, "{\"meta\":{\"id\":\"seven\"}}"));
        assertTrue(e.getMessage().contains("id"), "message: " + e.getMessage());
    }

    @Test
    void arrayItemSchemasAreEnforced() {
        String schema = "{\"type\":\"object\",\"properties\":{\"tags\":{\"type\":\"array\","
                + "\"items\":{\"type\":\"string\"}}}}";
        assertDoesNotThrow(() -> validator.validate(schema, "{\"tags\":[\"a\",\"b\"]}"));
        assertThrows(OutputSchemaException.class,
                () -> validator.validate(schema, "{\"tags\":[\"a\",2]}"));
    }

    @Test
    void numericBoundsAreEnforced() {
        String schema = "{\"type\":\"object\",\"properties\":{\"score\":"
                + "{\"type\":\"integer\",\"minimum\":1,\"maximum\":10}}}";
        assertDoesNotThrow(() -> validator.validate(schema, "{\"score\":5}"));
        assertThrows(OutputSchemaException.class, () -> validator.validate(schema, "{\"score\":0}"));
        assertThrows(OutputSchemaException.class, () -> validator.validate(schema, "{\"score\":11}"));
    }

    @Test
    void stringLengthIsEnforced() {
        String schema = "{\"type\":\"object\",\"properties\":{\"code\":"
                + "{\"type\":\"string\",\"minLength\":2,\"maxLength\":4}}}";
        assertDoesNotThrow(() -> validator.validate(schema, "{\"code\":\"abc\"}"));
        assertThrows(OutputSchemaException.class, () -> validator.validate(schema, "{\"code\":\"a\"}"));
        assertThrows(OutputSchemaException.class, () -> validator.validate(schema, "{\"code\":\"abcde\"}"));
    }

    @Test
    void patternIsEnforced() {
        String schema = "{\"type\":\"object\",\"properties\":{\"id\":"
                + "{\"type\":\"string\",\"pattern\":\"^[A-Z]{3}$\"}}}";
        assertDoesNotThrow(() -> validator.validate(schema, "{\"id\":\"ABC\"}"));
        assertThrows(OutputSchemaException.class, () -> validator.validate(schema, "{\"id\":\"ab\"}"));
    }

    @Test
    void anUnusableSchemaIsRejected() {
        // A schema whose "type" keyword is itself malformed (a number, not a type name) cannot compile.
        assertThrows(OutputSchemaException.class,
                () -> validator.validate("{ not even json", "{}"));
    }

    // --- property-style tests (JUnit 5; no third-party property lib, CLAUDE.md §11) -----------------

    /**
     * For a schema {@code {type:object, required:[answer], properties:{answer:{type:string}}}}, a reply is
     * accepted IFF it is a JSON object carrying a STRING {@code answer}. Curated edge cases plus
     * seeded-random object shapes assert the validator's accept/reject decision matches that predicate.
     */
    @ParameterizedTest
    @MethodSource("answerSchemaCases")
    void answerSchemaAcceptsExactlyAnObjectWithAStringAnswer(String reply, boolean shouldPass) {
        String schema = "{\"type\":\"object\",\"required\":[\"answer\"],"
                + "\"properties\":{\"answer\":{\"type\":\"string\"}}}";
        if (shouldPass) {
            assertDoesNotThrow(() -> validator.validate(schema, reply),
                    () -> "expected accept for: " + reply);
        } else {
            assertThrows(OutputSchemaException.class, () -> validator.validate(schema, reply),
                    () -> "expected reject for: " + reply);
        }
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> answerSchemaCases() {
        Stream<org.junit.jupiter.params.provider.Arguments> curated = Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("{\"answer\":\"x\"}", true),
                org.junit.jupiter.params.provider.Arguments.of("{\"answer\":\"\"}", true),
                org.junit.jupiter.params.provider.Arguments.of("{\"answer\":\"x\",\"extra\":1}", true),
                org.junit.jupiter.params.provider.Arguments.of("{\"answer\":1}", false),
                org.junit.jupiter.params.provider.Arguments.of("{\"answer\":null}", false),
                org.junit.jupiter.params.provider.Arguments.of("{\"answer\":true}", false),
                org.junit.jupiter.params.provider.Arguments.of("{\"answer\":[\"x\"]}", false),
                org.junit.jupiter.params.provider.Arguments.of("{}", false),
                org.junit.jupiter.params.provider.Arguments.of("[\"answer\"]", false),
                org.junit.jupiter.params.provider.Arguments.of("\"answer\"", false));

        // Seeded-random object shapes: a fixed seed keeps a failure reproducible (CLAUDE.md §11).
        Random rnd = new Random(124L);
        String[] values = {"\"ok\"", "42", "true", "null", "[1]", "{\"k\":1}"};
        Stream<org.junit.jupiter.params.provider.Arguments> random = IntStream.range(0, 24).mapToObj(i -> {
            String v = values[rnd.nextInt(values.length)];
            boolean present = rnd.nextBoolean();
            String reply = present ? "{\"answer\":" + v + "}" : "{\"noise\":" + v + "}";
            boolean pass = present && v.equals("\"ok\"");
            return org.junit.jupiter.params.provider.Arguments.of(reply, pass);
        });
        return Stream.concat(curated, random);
    }

    /** Every accepted reply round-trips: the returned {@code JsonNode} re-serializes to equivalent JSON. */
    @ParameterizedTest
    @ValueSource(strings = {"{\"answer\":\"x\"}", "{\"answer\":\"y\",\"n\":1}", "{\"answer\":\"\"}"})
    void anAcceptedReplyRoundTripsThroughTheValidatedNode(String reply) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = validator.validate(
                "{\"type\":\"object\",\"required\":[\"answer\"],"
                        + "\"properties\":{\"answer\":{\"type\":\"string\"}}}",
                reply);
        assertEquals(mapper.readTree(reply), mapper.readTree(mapper.writeValueAsString(node)));
    }
}
