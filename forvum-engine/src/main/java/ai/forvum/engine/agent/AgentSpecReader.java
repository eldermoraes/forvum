package ai.forvum.engine.agent;

import ai.forvum.core.ModelRef;
import ai.forvum.core.Persona;
import ai.forvum.core.id.AgentId;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the raw agent surface — the free-form Markdown persona and the structural JSON spec delivered
 * by the M4 {@code AgentReader} — into a typed core {@link Persona} (ULTRAPLAN section 5.2). The
 * {@code .md} becomes the system prompt; the {@code .json} supplies the allowed-tool globs, the LLM
 * model, an optional parent pointer, and budget caps. v0.1 maps the spec's single {@code primaryModel}
 * — the fallback chain and memory policy are deferred ({@link Persona} omits them by design,
 * section 4.3.4).
 */
public final class AgentSpecReader {

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
            toolBudget = toolBudgetNode.asLong();
        }

        // costBudget parsing (nested CostBudget) is deferred to a later M7 increment; absent -> null.
        return new Persona(id, systemPrompt, allowedTools, primaryModel, parent, null, toolBudget);
    }
}
