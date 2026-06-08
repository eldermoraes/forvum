package ai.forvum.engine.agent;

import ai.forvum.core.ModelRef;
import ai.forvum.core.Persona;
import ai.forvum.core.id.AgentId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the raw agent surface — the free-form Markdown persona and the structural JSON spec delivered
 * by the M4 {@code AgentReader} — into a typed core {@link Persona} (ULTRAPLAN section 5.2). The
 * {@code .md} becomes the system prompt; the {@code .json} supplies the allowed-tool globs, the LLM
 * model, an optional parent pointer, and budget caps. v0.1 maps the spec's single {@code primaryModel}
 * — the fallback chain and memory policy are deferred ({@link Persona} omits them by design,
 * section 4.3.4). An optional {@code outputSchema} (P2-12) is carried through to the engine as a raw
 * JSON-Schema string that constrains the turn's final reply.
 */
public final class AgentSpecReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Bind a raw persona + spec to a {@link Persona}.
     *
     * @throws IllegalStateException if {@code primaryModel} is absent/blank, or any value fails its
     *         core record's canonical-constructor validation — with text naming
     *         {@code agents/<id>.json} so the operator can fix the file.
     */
    public Persona parse(AgentId id, String systemPrompt, JsonNode spec) {
        JsonNode modelNode = spec.get("primaryModel");
        if (modelNode == null || modelNode.isNull() || modelNode.asText().isBlank()) {
            throw new IllegalStateException(
                "Agent '" + id.value() + "' is missing the required 'primaryModel' field. "
              + "Check agents/" + id.value() + ".json.");
        }
        ModelRef primaryModel = ModelRef.parse(modelNode.asText());

        List<String> allowedTools = new ArrayList<>();
        JsonNode toolsNode = spec.get("allowedTools");
        if (toolsNode != null && toolsNode.isArray()) {
            toolsNode.forEach(t -> allowedTools.add(t.asText()));
        }

        AgentId parent = null;
        JsonNode parentNode = spec.get("parent");
        if (parentNode != null && !parentNode.isNull() && !parentNode.asText().isBlank()) {
            parent = new AgentId(parentNode.asText());
        }

        Long toolBudget = null;
        JsonNode toolBudgetNode = spec.get("toolBudget");
        if (toolBudgetNode != null && !toolBudgetNode.isNull()) {
            if (!toolBudgetNode.isIntegralNumber()) {
                throw new IllegalStateException(
                    "Agent '" + id.value() + "' has a non-integer 'toolBudget' (" + toolBudgetNode
                  + "). Check agents/" + id.value() + ".json.");
            }
            toolBudget = toolBudgetNode.asLong();
        }

        String outputSchema = parseOutputSchema(id, spec.get("outputSchema"));

        // costBudget parsing (nested CostBudget) is deferred to a later M7 increment; absent -> null.
        return new Persona(id, systemPrompt, allowedTools, primaryModel, parent, null, toolBudget,
                outputSchema);
    }

    /**
     * Carry the optional {@code outputSchema} field through as a raw JSON-Schema {@code String}. The spec
     * normally embeds the schema as a JSON object ({@code "outputSchema": { ... }}) which is re-serialized
     * to its compact text form; a plain JSON string is accepted too (operators that paste a pre-built
     * schema). Absent/null -> {@code null} (free-text output, the backward-compatible default).
     */
    private static String parseOutputSchema(AgentId id, JsonNode schemaNode) {
        if (schemaNode == null || schemaNode.isNull()) {
            return null;
        }
        if (schemaNode.isObject()) {
            try {
                return MAPPER.writeValueAsString(schemaNode);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(
                    "Agent '" + id.value() + "' has an 'outputSchema' that could not be serialized. "
                  + "Check agents/" + id.value() + ".json.", e);
            }
        }
        if (schemaNode.isTextual()) {
            return schemaNode.asText();
        }
        throw new IllegalStateException(
            "Agent '" + id.value() + "' has an 'outputSchema' that is neither a JSON object nor a string "
          + "(got " + schemaNode.getNodeType() + "). Check agents/" + id.value() + ".json.");
    }
}
