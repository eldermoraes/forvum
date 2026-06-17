package ai.forvum.core;

/**
 * A tool the agent runtime can invoke (ULTRAPLAN sections 5.3, 4.3 backfill). Contributed by a
 * {@code ToolProvider} into the global ToolRegistry, then filtered into an agent's tool belt by the
 * persona's {@code allowedTools} globs. {@code requiredScope} is the capability the engine's
 * ToolExecutor enforces before invocation. {@code parametersJsonSchema} is the raw JSON Schema of the
 * tool's arguments (use {@code "{}"} for no parameters); adaptation to LangChain4j's
 * {@code ToolSpecification} happens in the SDK/engine, keeping this Layer-0 type free of AI-library
 * coupling.
 *
 * <p>{@code userConfirmRequired} (P2-14 #39, ULTRAPLAN §7.2 item 14 / §9.1.b DP-9) opts a tool in to the
 * blocking user-approval gate: when {@code true}, the engine's {@code ToolExecutor} parks every invocation
 * through the SQLite-backed approval queue (the owner approves or rejects; an unresolved confirmation
 * times out to deny) <em>after</em> the belt and RBAC scope gates pass. A destructive tool such as
 * {@code shell.exec} (#27) declares it {@code true}; the common case is {@code false}, the value the
 * backward-compatible 4-argument constructor supplies so every pre-#39 call site is unchanged.
 */
public record ToolSpec(String name, String description, PermissionScope requiredScope,
                       String parametersJsonSchema, boolean userConfirmRequired) {
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

    /**
     * Backward-compatible constructor for a tool that does NOT require user approval (the common case),
     * delegating to the canonical constructor with {@code userConfirmRequired = false}. Every pre-#39 call
     * site uses this form, so adding the approval flag is purely additive — no caller or config changes.
     */
    public ToolSpec(String name, String description, PermissionScope requiredScope,
                    String parametersJsonSchema) {
        this(name, description, requiredScope, parametersJsonSchema, false);
    }
}
