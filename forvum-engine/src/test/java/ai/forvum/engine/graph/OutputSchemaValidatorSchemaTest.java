package ai.forvum.engine.graph;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * {@link OutputSchemaValidator#validateSchema} — the schema-WELL-FORMEDNESS check made public for P2-7
 * (the skill front-matter {@code inputSchema}, validated on read). It is distinct from the value-side
 * {@code validate}: it checks the DECLARED schema is itself valid (JSON object; {@code required} an
 * array; {@code properties} an object), reusing the same v0.5 subset so there is no parallel definition.
 */
class OutputSchemaValidatorSchemaTest {

    private final OutputSchemaValidator validator = new OutputSchemaValidator(new ObjectMapper());

    @Test
    void aWellFormedSchemaPasses() {
        assertDoesNotThrow(() -> validator.validateSchema(
                "{ \"type\": \"object\", \"required\": [\"text\"], "
                        + "\"properties\": { \"text\": { \"type\": \"string\" } } }"));
    }

    @Test
    void anEmptyObjectSchemaPasses() {
        assertDoesNotThrow(() -> validator.validateSchema("{}"));
    }

    @Test
    void nonJsonIsRejected() {
        assertThrows(OutputSchemaException.class, () -> validator.validateSchema("{ not json"));
    }

    @Test
    void aNonObjectSchemaIsRejected() {
        assertThrows(OutputSchemaException.class, () -> validator.validateSchema("[1, 2, 3]"));
        assertThrows(OutputSchemaException.class, () -> validator.validateSchema("\"a string\""));
    }

    @Test
    void aNonArrayRequiredIsRejected() {
        OutputSchemaException e = assertThrows(OutputSchemaException.class,
                () -> validator.validateSchema("{ \"required\": \"text\" }"));
        assertTrue(e.getMessage().contains("required"));
    }

    @Test
    void aNonObjectPropertiesIsRejected() {
        OutputSchemaException e = assertThrows(OutputSchemaException.class,
                () -> validator.validateSchema("{ \"properties\": [] }"));
        assertTrue(e.getMessage().contains("properties"));
    }
}
