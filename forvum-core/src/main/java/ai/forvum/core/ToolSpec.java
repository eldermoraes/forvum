package ai.forvum.core;

/**
 * A tool the agent runtime can invoke (ULTRAPLAN sections 5.3, 4.3 backfill). Contributed by a
 * {@code ToolProvider} into the global ToolRegistry, then filtered into an agent's tool belt by the
 * persona's {@code allowedTools} globs. {@code requiredScope} is the capability the engine's
 * ToolExecutor enforces before invocation. {@code parametersJsonSchema} is the raw JSON Schema of the
 * tool's arguments (use {@code "{}"} for no parameters); adaptation to LangChain4j's
 * {@code ToolSpecification} happens in the SDK/engine, keeping this Layer-0 type free of AI-library
 * coupling.
 */
public record ToolSpec(String name, String description, PermissionScope requiredScope,
                       String parametersJsonSchema) {
    public ToolSpec {
        if (name == null || name.isBlank() || !name.strip().equals(name)) {
            throw new IllegalStateException(
                "ToolSpec name must be a non-blank token without leading/trailing whitespace. "
              + "Got: '" + name + "'.");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalStateException(
                "ToolSpec description must be non-null and non-blank — the model relies on it for "
              + "tool selection. Got: '" + description + "'.");
        }
        if (requiredScope == null) {
            throw new IllegalStateException(
                "ToolSpec requiredScope must be non-null. Every tool declares the PermissionScope it "
              + "requires.");
        }
        if (parametersJsonSchema == null) {
            throw new IllegalStateException(
                "ToolSpec parametersJsonSchema must be non-null (use '{}' for no parameters).");
        }
    }
}
