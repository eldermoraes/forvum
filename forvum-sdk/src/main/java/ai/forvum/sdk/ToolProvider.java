package ai.forvum.sdk;

import ai.forvum.core.ToolSpec;

import java.util.List;
import java.util.Map;

/**
 * SPI a tool plugin implements to contribute tools (and their {@code ai.forvum.core.PermissionScope})
 * to the global tool registry (ULTRAPLAN section 2.2, Layer 1). Sealed: third parties extend
 * {@link AbstractToolProvider}. M3 fixed only the structural contract and the id; the contribution
 * method ({@link #tools()}, carrying {@link ToolSpec}) is the M13 prelude that M14's filesystem plugin
 * implements. M18 (Option A) adds the execution method {@link #invoke(String, Map)} here too: the
 * engine's ToolExecutor still gates permission + audits every call, but dispatch is the provider's own
 * (a name→logic switch), so there is no reflection and no AI library leaks into the engine. The contract
 * carries only {@code java.*} + {@code forvum-core} types (no Quarkus, no AI library).
 */
public sealed interface ToolProvider permits AbstractToolProvider {

    /** Stable id of the contributing extension, matching its {@code META-INF/forvum/plugin.json}. */
    String extensionId();

    /**
     * The tools this extension contributes to the global ToolRegistry. Each {@link ToolSpec} carries the
     * {@code ai.forvum.core.PermissionScope} the engine's ToolExecutor enforces before invocation, and
     * the persona's {@code allowedTools} globs later filter these into an agent's tool belt.
     *
     * <p>The engine's ToolRegistry discovers every {@code ToolProvider} via CDI and registers each
     * returned spec under its {@link ToolSpec#name()}; implementations return the static list of tools
     * they expose (they do not touch the registry, filtering, or execution — those are the engine's
     * concern, ULTRAPLAN section 5.3).
     */
    List<ToolSpec> tools();

    /**
     * Execute the tool named {@code toolName} with {@code arguments} (parsed from the model's tool-call
     * JSON), returning the result serialized to a string (ULTRAPLAN section 5.5, M18 Option A). The engine
     * resolves the owning provider via {@code ToolRegistry.providerFor(name)} and runs this call inside its
     * {@code ToolExecutor}, which enforces the agent's belt (a tool outside it never reaches here — it is
     * refused with {@code PermissionDeniedException} and audited {@code denied}) and audits the outcome
     * {@code ok}/{@code error}. Implementations therefore dispatch by name (no reflection) and may assume
     * the call is already permitted; a name this provider does not contribute is a programming error
     * ({@link IllegalArgumentException}); a tool failure throws (the engine audits it {@code error} and
     * rethrows).
     *
     * @param toolName  the {@link ToolSpec#name()} of one of this provider's {@link #tools()}
     * @param arguments the tool arguments by name (values are the parsed JSON scalars/objects/arrays)
     * @return the tool's result, serialized to a string for the model
     */
    String invoke(String toolName, Map<String, Object> arguments);
}
