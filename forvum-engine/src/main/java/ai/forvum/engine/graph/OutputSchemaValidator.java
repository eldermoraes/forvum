package ai.forvum.engine.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion.VersionFlag;
import com.networknt.schema.ValidationMessage;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates a per-agent {@code outputSchema} (P2-12) against the supervisor's final assistant reply. When
 * an agent declares an {@code outputSchema}, the final text must parse as JSON and satisfy the schema; this
 * class decodes the text and validates it, NAMING the offending field(s) on failure (the message rides into
 * a terminal {@code ErrorEvent} — no retry).
 *
 * <p><strong>Engine (#124):</strong> validation is delegated to the native-clean
 * {@code com.networknt:json-schema-validator} (default dialect JSON Schema <strong>draft 2020-12</strong>),
 * replacing the original hand-rolled v0.5-parity subset (root {@code type} / {@code required} / each
 * property's primitive {@code type}). The library now provides the full draft surface — {@code enum},
 * nested object/array schemas, numeric bounds ({@code minimum}/{@code maximum}/{@code multipleOf}),
 * string constraints ({@code minLength}/{@code maxLength}/{@code pattern}), {@code format} annotations, and
 * the {@code allOf}/{@code anyOf}/{@code oneOf} combinators. It is native-clean by construction: the
 * library ships an EMPTY {@code reflect-config.json} + a {@code resource-config.json} for its bundled
 * meta-schemas (no runtime reflection, no {@code META-INF/services} {@code ServiceLoader}), so the GraalVM
 * native binary needs no Forvum-authored hint (CLAUDE.md §5). The optional ECMA-262 regex engines
 * (GraalJS / Joni) are deliberately NOT on the classpath; the JDK regex implementation backs {@code pattern}.
 *
 * <p>The validator is stateless and depends only on a shared {@link ObjectMapper} and a single
 * application-wide {@link JsonSchemaFactory}; it carries no DTO record, so it needs no
 * {@code @RegisterForReflection}.
 */
public final class OutputSchemaValidator {

    /**
     * Default dialect for a schema that omits {@code $schema}. Draft 2020-12 is the current JSON Schema
     * draft; a schema declaring an older {@code $schema} is honored, this only sets the fallback.
     */
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(VersionFlag.V202012);

    private final ObjectMapper mapper;

    public OutputSchemaValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Validate that {@code schemaJson} is itself a usable schema — used to validate a DECLARED schema at
     * config-read time (the skill front-matter {@code inputSchema}, P2-7). Checks: valid JSON; a JSON
     * object; {@code required} (if present) an array; {@code properties} (if present) an object; and that
     * the document compiles as a schema. These structural guards are retained from the original validator
     * (the {@code SkillReader} contract) and complement the compile so a clearly-malformed schema is
     * rejected at config-read with a precise message rather than surfacing as a confusing validation error
     * at turn time. Throws {@link OutputSchemaException} naming the problem.
     */
    public void validateSchema(String schemaJson) {
        JsonNode schema;
        try {
            schema = mapper.readTree(schemaJson);
        } catch (JsonProcessingException e) {
            throw new OutputSchemaException(
                "the declared input schema is not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (schema == null || !schema.isObject()) {
            throw new OutputSchemaException("the declared input schema must be a JSON object.", null);
        }
        JsonNode required = schema.get("required");
        if (required != null && !required.isArray()) {
            throw new OutputSchemaException(
                "the declared input schema's 'required' must be an array of field names.", null);
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && !properties.isObject()) {
            throw new OutputSchemaException(
                "the declared input schema's 'properties' must be an object.", null);
        }
        try {
            FACTORY.getSchema(schemaJson, InputFormat.JSON);
        } catch (RuntimeException e) {
            throw new OutputSchemaException(
                "the declared input schema is not a usable JSON Schema: " + e.getMessage(), e);
        }
    }

    /**
     * Validate {@code finalText} against {@code schemaJson} and return the decoded {@link JsonNode}.
     *
     * @throws OutputSchemaException if {@code finalText} is not valid JSON, the schema itself is not a
     *         usable JSON Schema, or the decoded value violates the schema. The message lists the failing
     *         instance location(s) and the validation reason(s) reported by the JSON-Schema engine.
     */
    JsonNode validate(String schemaJson, String finalText) {
        JsonSchema schema;
        try {
            schema = FACTORY.getSchema(schemaJson, InputFormat.JSON);
        } catch (RuntimeException e) {
            throw new OutputSchemaException(
                "the declared outputSchema is not a usable JSON Schema: " + e.getMessage(), e);
        }

        JsonNode value;
        try {
            value = mapper.readTree(finalText);
        } catch (JsonProcessingException e) {
            throw new OutputSchemaException(
                "the model reply is not valid JSON for the declared outputSchema: " + e.getOriginalMessage(),
                e);
        }
        if (value == null || value.isMissingNode()) {
            throw new OutputSchemaException(
                "the model reply is empty and cannot satisfy the declared outputSchema.", null);
        }

        Set<ValidationMessage> errors = schema.validate(value);
        if (!errors.isEmpty()) {
            throw new OutputSchemaException(
                "the model reply does not satisfy the declared outputSchema: " + describe(errors), null);
        }
        return value;
    }

    /**
     * Render the JSON-Schema engine's validation messages into a single human-readable summary. Each
     * message already names the failing instance location (e.g. {@code $.answer}) and the reason (e.g.
     * {@code integer found, string expected}); messages are joined in a stable (sorted) order so the
     * summary is deterministic for tests and logs.
     */
    private static String describe(Set<ValidationMessage> errors) {
        return errors.stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .collect(Collectors.joining("; "));
    }
}
