package ai.forvum.engine.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.Map;

/**
 * A minimal, pure-Java validator for a per-agent {@code outputSchema} (P2-12). When an agent declares an
 * {@code outputSchema}, the supervisor's final assistant text must parse as JSON and satisfy the schema;
 * this class decodes the text and checks it against the declared shape, NAMING the offending field on
 * failure (the message rides into a terminal {@code ErrorEvent} — no retry, CLAUDE.md section 5 keeps this
 * native-clean by avoiding a typed POJO + reflection).
 *
 * <p><strong>Scope (v0.5 parity):</strong> this validates the subset of JSON Schema that matters for a
 * structured-output guard — the root {@code type} (default {@code "object"}), the {@code required} field
 * list, and each declared property's primitive {@code type} ({@code string}/{@code number}/
 * {@code integer}/{@code boolean}/{@code object}/{@code array}/{@code null}). It does NOT implement
 * nested-schema recursion, {@code enum}, {@code format}, {@code pattern}, or composition keywords
 * ({@code allOf}/{@code anyOf}/{@code oneOf}); full JSON-Schema-draft validation is a documented
 * fast-follow (it would pull a JSON-Schema library that must be proven to native-compile first). The
 * validator is pure Jackson tree-walking — no reflection, no dynamic class loading.
 *
 * <p>The validator is deliberately stateless and depends only on a shared {@link ObjectMapper}; it carries
 * no DTO record, so it needs no {@code @RegisterForReflection}.
 */
final class OutputSchemaValidator {

    private final ObjectMapper mapper;

    OutputSchemaValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Validate {@code finalText} against {@code schemaJson} and return the decoded {@link JsonNode}.
     *
     * @throws OutputSchemaException if {@code finalText} is not valid JSON, the schema itself is not valid
     *         JSON, or the decoded value violates the schema (wrong root type, missing required field, or a
     *         declared property of the wrong primitive type). The message names the schema and the failure.
     */
    JsonNode validate(String schemaJson, String finalText) {
        JsonNode schema;
        try {
            schema = mapper.readTree(schemaJson);
        } catch (JsonProcessingException e) {
            throw new OutputSchemaException(
                "the declared outputSchema is not valid JSON: " + e.getOriginalMessage(), e);
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

        validateType(schema, "(root)", value, expectedRootType(schema));
        validateRequiredAndProperties(schema, value);
        return value;
    }

    private static String expectedRootType(JsonNode schema) {
        JsonNode typeNode = schema.get("type");
        return typeNode != null && typeNode.isTextual() ? typeNode.asText() : "object";
    }

    private void validateRequiredAndProperties(JsonNode schema, JsonNode value) {
        // required + per-property type checks only apply to an object value with an object schema.
        if (!value.isObject()) {
            return;
        }
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode field : required) {
                String name = field.asText();
                if (!value.has(name) || value.get(name).isNull()) {
                    throw new OutputSchemaException(
                        "the model reply is missing the required field '" + name
                      + "' declared by the outputSchema.", null);
                }
            }
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> property = it.next();
                String name = property.getKey();
                JsonNode declared = property.getValue();
                JsonNode present = value.get(name);
                if (present == null || present.isNull()) {
                    // Absent optional properties are fine; missing required ones already failed above.
                    continue;
                }
                JsonNode typeNode = declared.get("type");
                if (typeNode != null && typeNode.isTextual()) {
                    validateType(declared, name, present, typeNode.asText());
                }
            }
        }
    }

    private void validateType(JsonNode schema, String fieldName, JsonNode value, String expectedType) {
        if (expectedType == null || matchesType(expectedType, value)) {
            return;
        }
        throw new OutputSchemaException(
            "the model reply field '" + fieldName + "' is a " + actualType(value)
          + " but the outputSchema declares it as " + expectedType + ".", null);
    }

    private static boolean matchesType(String expected, JsonNode value) {
        return switch (expected) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            // An unknown/unsupported type keyword is not enforced (documented fast-follow).
            default -> true;
        };
    }

    private static String actualType(JsonNode value) {
        if (value.isObject()) {
            return "object";
        }
        if (value.isArray()) {
            return "array";
        }
        if (value.isTextual()) {
            return "string";
        }
        if (value.isIntegralNumber()) {
            return "integer";
        }
        if (value.isNumber()) {
            return "number";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        if (value.isNull()) {
            return "null";
        }
        return value.getNodeType().toString().toLowerCase(java.util.Locale.ROOT);
    }
}
