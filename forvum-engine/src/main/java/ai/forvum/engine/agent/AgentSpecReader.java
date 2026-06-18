package ai.forvum.engine.agent;

import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.MemoryTier;
import ai.forvum.core.ModelRef;
import ai.forvum.core.Persona;
import ai.forvum.core.RetrievalStrategy;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.graph.CycleSpec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses the raw agent surface — the free-form Markdown persona and the structural JSON spec delivered
 * by the M4 {@code AgentReader} — into a typed core {@link Persona} (ULTRAPLAN §5.2) and, via
 * {@link #parseSpec}, the engine-side {@link AgentSpec} (persona + optional declared cycle). The
 * {@code .md} becomes the system prompt; the {@code .json} supplies the allowed-tool globs, the LLM
 * model, the fallback chain, the retrieval policy, the role cap, the identity pointer, an optional
 * parent pointer, and budget caps (DR-8, §4.3.8). Every key but {@code primaryModel} is optional with a
 * backward-compatible default, so a spec predating the DR-8 fields parses unchanged. {@code costBudget}
 * parsing stays deferred (PR-9, DR-8 DP-6); absent ⇒ {@code null} today.
 */
public final class AgentSpecReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Bind a raw persona + spec to a {@link Persona} (the 12-field DR-8 composition).
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
        List<ModelRef> fallbackModels = parseFallbackModels(id, spec.get("fallbackModels"));
        MemoryPolicy memoryPolicy = parseMemoryPolicy(id, spec.get("memoryPolicy"));
        List<String> roles = parseRoles(id, spec.get("roles"));
        String identityId = parseIdentityId(spec.get("identityId"));

        // costBudget parsing (nested CostBudget) stays deferred to PR-9 (DR-8 DP-6); absent -> null.
        return new Persona(id, systemPrompt, allowedTools, primaryModel, parent, null, toolBudget,
                outputSchema, fallbackModels, memoryPolicy, roles, identityId);
    }

    /**
     * Bind a raw persona + spec to the full {@link AgentSpec}: the {@link Persona} plus the optional
     * declared {@link CycleSpec} from the {@code "cycle"} block (DR-8 DP-7; absent ⇒ {@code null}, the
     * standard supervisor graph). The §5.2 registry value.
     */
    public AgentSpec parseSpec(AgentId id, String systemPrompt, JsonNode spec) {
        return new AgentSpec(parse(id, systemPrompt, spec), parseCycle(id, spec.get("cycle")));
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

    /**
     * The ordered LLM fallback chain — an array of {@code ModelRef.parse} strings tried after
     * {@code primaryModel} (§5.4). Absent/null -> {@code []} (primary-only chain, today's behavior). The
     * engine composes the Group-4c chain from {@code primaryModel} + this list at materialization.
     */
    private static List<ModelRef> parseFallbackModels(AgentId id, JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalStateException(
                "Agent '" + id.value() + "' 'fallbackModels' must be a JSON array of model refs. "
              + "Check agents/" + id.value() + ".json.");
        }
        List<ModelRef> refs = new ArrayList<>();
        node.forEach(n -> refs.add(ModelRef.parse(n.asText())));
        return refs;
    }

    /**
     * The retrieval policy block. Absent/null -> {@code null} (the {@link Persona} canonical constructor
     * normalizes it to {@link MemoryPolicy#defaults()} — the single config-absent source, DR-5 DP-6). A
     * present block defaults each omitted field from {@code defaults()} (DR-8 DP-5); enum fields parse
     * case-insensitively and an unknown value throws naming the field and file. All range invariants
     * stay in {@code MemoryPolicy}'s canonical constructor (reader-as-oracle, P2-9).
     */
    private static MemoryPolicy parseMemoryPolicy(AgentId id, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalStateException(
                "Agent '" + id.value() + "' 'memoryPolicy' must be a JSON object. "
              + "Check agents/" + id.value() + ".json.");
        }
        MemoryPolicy d = MemoryPolicy.defaults();
        RetrievalStrategy strategy = node.has("strategy")
                ? parseStrategy(id, node.get("strategy")) : d.strategy();
        Set<MemoryTier> tiers = node.has("tiers")
                ? parseTiers(id, node.get("tiers")) : d.tiers();
        int topK = node.has("topK")
                ? requireInt(id, "memoryPolicy.topK", node.get("topK")) : d.topK();
        double minScore = node.has("minScore")
                ? requireNumber(id, "memoryPolicy.minScore", node.get("minScore")) : d.minScore();
        int compress = node.has("compressThresholdChars")
                ? requireInt(id, "memoryPolicy.compressThresholdChars", node.get("compressThresholdChars"))
                : d.compressThresholdChars();
        return new MemoryPolicy(strategy, tiers, topK, minScore, compress);
    }

    private static RetrievalStrategy parseStrategy(AgentId id, JsonNode n) {
        try {
            return RetrievalStrategy.valueOf(n.asText().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "Agent '" + id.value() + "' 'memoryPolicy.strategy' is not a known strategy (got '"
              + n.asText() + "'). Expected one of VECTOR/GRAPH/METADATA/HYBRID/NONE. "
              + "Check agents/" + id.value() + ".json.");
        }
    }

    private static Set<MemoryTier> parseTiers(AgentId id, JsonNode n) {
        if (!n.isArray()) {
            throw new IllegalStateException(
                "Agent '" + id.value() + "' 'memoryPolicy.tiers' must be a JSON array of tier names. "
              + "Check agents/" + id.value() + ".json.");
        }
        Set<MemoryTier> tiers = EnumSet.noneOf(MemoryTier.class);
        for (JsonNode t : n) {
            try {
                tiers.add(MemoryTier.valueOf(t.asText().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                    "Agent '" + id.value() + "' 'memoryPolicy.tiers' has an unknown tier (got '"
                  + t.asText() + "'). Expected one of MESSAGES/EPISODIC/SEMANTIC. "
                  + "Check agents/" + id.value() + ".json.");
            }
        }
        return tiers;
    }

    /**
     * The agent-level scope-cap role names (§4.3.4). Absent/null -> {@code []} (no cap). Each name's
     * non-blankness is enforced by the {@link Persona} canonical constructor.
     */
    private static List<String> parseRoles(AgentId id, JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalStateException(
                "Agent '" + id.value() + "' 'roles' must be a JSON array of role names. "
              + "Check agents/" + id.value() + ".json.");
        }
        List<String> roles = new ArrayList<>();
        node.forEach(n -> roles.add(n.asText()));
        return roles;
    }

    /** The identity pointer (§5.3). Absent/null/blank -> {@code null} (anonymous fallback unchanged). */
    private static String parseIdentityId(JsonNode node) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }

    /**
     * The optional declared reflection cycle (DR-8 DP-7, the #51 consumer). Absent/null -> {@code null}
     * (standard supervisor graph). {@code steps} is required (non-empty array); {@code maxRounds}
     * defaults to 3; {@code stopSentinel} defaults to null. All field invariants stay in
     * {@link CycleSpec}'s canonical constructor.
     */
    private static CycleSpec parseCycle(AgentId id, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalStateException(
                "Agent '" + id.value() + "' 'cycle' must be a JSON object. "
              + "Check agents/" + id.value() + ".json.");
        }
        JsonNode stepsNode = node.get("steps");
        if (stepsNode == null || !stepsNode.isArray()) {
            throw new IllegalStateException(
                "Agent '" + id.value() + "' 'cycle.steps' must be a non-empty JSON array of instruction "
              + "strings. Check agents/" + id.value() + ".json.");
        }
        List<String> steps = new ArrayList<>();
        stepsNode.forEach(s -> steps.add(s.asText()));
        int maxRounds = node.has("maxRounds")
                ? requireInt(id, "cycle.maxRounds", node.get("maxRounds")) : 3;
        JsonNode sentinelNode = node.get("stopSentinel");
        String stopSentinel = (sentinelNode == null || sentinelNode.isNull()) ? null : sentinelNode.asText();
        return new CycleSpec(steps, maxRounds, stopSentinel);
    }

    private static int requireInt(AgentId id, String field, JsonNode n) {
        if (!n.isNumber()) {
            throw new IllegalStateException(
                "Agent '" + id.value() + "' '" + field + "' must be a number (got " + n + "). "
              + "Check agents/" + id.value() + ".json.");
        }
        return n.asInt();
    }

    private static double requireNumber(AgentId id, String field, JsonNode n) {
        if (!n.isNumber()) {
            throw new IllegalStateException(
                "Agent '" + id.value() + "' '" + field + "' must be a number (got " + n + "). "
              + "Check agents/" + id.value() + ".json.");
        }
        return n.asDouble();
    }
}
