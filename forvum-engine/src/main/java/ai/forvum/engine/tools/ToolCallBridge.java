package ai.forvum.engine.tools;

import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.sdk.ToolProvider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The M18 seam (ULTRAPLAN section 5.5, Option A) that turns a model-emitted tool call into a
 * permission-gated, audited provider invocation — the point where M13/M14's tool substrate finally
 * runs inside a turn. The bridge resolves the owning provider via {@link ToolRegistry#providerFor},
 * parses the tool-call JSON to a {@code Map}, and runs the call inside {@link ToolExecutor}, which is
 * the single permission + audit gate: a tool outside the agent's {@code belt} is refused
 * ({@link PermissionDeniedException}) and audited {@code denied} without the provider ever running; a
 * permitted call is dispatched and audited {@code ok}; a failing call (including malformed arguments)
 * is audited {@code error}. No reflection, no AI library — the provider self-dispatches by name.
 */
@ApplicationScoped
public class ToolCallBridge {

    private static final TypeReference<Map<String, Object>> ARGS = new TypeReference<Map<String, Object>>() {};

    @Inject
    ToolRegistry registry;

    @Inject
    ToolExecutor toolExecutor;

    @Inject
    ObjectMapper mapper;

    /**
     * Execute the model's call to {@code toolName} with {@code argsJson} on behalf of {@code agentId},
     * gated by {@code belt} and audited. The raw {@code argsJson} is what the audit row records; it is
     * parsed to a {@code Map} only inside the permitted call, so a denied tool never parses arguments
     * (and malformed arguments on a permitted call are audited {@code error}).
     *
     * @return the tool's result, serialized to a string for the model
     * @throws PermissionDeniedException if {@code toolName} is not in {@code belt}
     */
    public String dispatch(String sessionId, AgentId agentId, List<ToolSpec> belt,
            String toolName, String argsJson) {
        return toolExecutor.execute(sessionId, agentId, belt, toolName, argsJson, () -> {
            ToolProvider owner = registry.providerFor(toolName);
            if (owner == null) {
                throw new IllegalStateException(
                        "Tool '" + toolName + "' is in the agent's belt but no provider contributes it "
                      + "(the registry and the belt have diverged).");
            }
            return owner.invoke(toolName, parseArguments(argsJson));
        });
    }

    private Map<String, Object> parseArguments(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(argsJson, ARGS);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Tool arguments are not valid JSON: " + argsJson, e);
        }
    }

    /**
     * Build the model-facing {@link ToolSpecification}s for an agent's belt from each {@link ToolSpec}'s
     * name, description, and {@code parametersJsonSchema} — with NO reflection (the M18 tool_loop offers
     * these to the model). The JSON-schema string is parsed into a {@link JsonObjectSchema}; v0.1 handles
     * flat {@code string}/{@code integer}/{@code number}/{@code boolean} properties + {@code required}
     * (the filesystem tools' shape). Richer schemas (nested objects, arrays, enums) are a later extension.
     */
    public List<ToolSpecification> specificationsFor(List<ToolSpec> belt) {
        List<ToolSpecification> specifications = new ArrayList<>(belt.size());
        for (ToolSpec tool : belt) {
            specifications.add(ToolSpecification.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .parameters(objectSchema(tool))
                    .build());
        }
        return specifications;
    }

    private JsonObjectSchema objectSchema(ToolSpec tool) {
        JsonObjectSchema.Builder schema = JsonObjectSchema.builder();
        JsonNode root;
        try {
            root = mapper.readTree(tool.parametersJsonSchema());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Tool '" + tool.name() + "' has an invalid parametersJsonSchema: "
                  + tool.parametersJsonSchema(), e);
        }
        JsonNode properties = root.get("properties");
        if (properties != null) {
            properties.fields().forEachRemaining(entry -> addProperty(schema, entry.getKey(), entry.getValue()));
        }
        JsonNode required = root.get("required");
        if (required != null && required.isArray()) {
            List<String> names = new ArrayList<>();
            required.forEach(name -> names.add(name.asText()));
            schema.required(names);
        }
        return schema.build();
    }

    private static void addProperty(JsonObjectSchema.Builder schema, String name, JsonNode property) {
        String type = property.path("type").asText("string");
        String description = property.hasNonNull("description") ? property.get("description").asText() : null;
        switch (type) {
            case "integer" -> schema.addIntegerProperty(name, description);
            case "number" -> schema.addNumberProperty(name, description);
            case "boolean" -> schema.addBooleanProperty(name, description);
            default -> schema.addStringProperty(name, description);
        }
    }
}
