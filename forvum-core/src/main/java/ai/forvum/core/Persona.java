package ai.forvum.core;

import java.util.List;

import ai.forvum.core.budget.CostBudget;
import ai.forvum.core.id.AgentId;

/**
 * The structural configuration of an agent (ULTRAPLAN sections 5.2, 4.3 backfill). {@code systemPrompt}
 * is the {@code agents/<id>.md} prose; the remaining fields are the {@code agents/<id>.json} structural
 * spec. {@code allowedTools} are tool-name globs intersected against the global ToolRegistry at
 * materialization. {@code parent}, {@code costBudget}, and {@code toolBudget} are nullable: null parent
 * means a top-level agent, null caps mean uncapped.
 *
 * <p>The LLM fallback chain and the retrieval policy landed with DR-4c/DR-5 and are composed here
 * (DR-8, §4.3.8): {@code fallbackModels} is the ordered list of refs tried after {@code primaryModel}
 * ({@code []} = primary-only — today's behavior); {@code memoryPolicy} drives the {@code MemoryProvider}
 * ({@code null} normalizes to {@link MemoryPolicy#defaults()} — "memory off" is the value
 * {@code strategy=NONE}, not absence); {@code roles} are agent-level scope-cap role names ({@code []} =
 * no cap, intersected above the caller's scopes at the turn entry); {@code identityId} points at the
 * {@code identities/<id>.json} this agent runs as ({@code null} = anonymous fallback unchanged). All
 * four are additive with backward-compatible defaults — an {@code agents/<id>.json} predating them
 * binds to the same values the 8-argument constructor supplies (the {@code Identity.roles} precedent).
 *
 * <p>{@code allowedTools}, {@code fallbackModels}, and {@code roles} are defensively copied to immutable
 * lists by the canonical constructor; a null {@code roles}/{@code fallbackModels} normalizes to an empty
 * list and a null {@code memoryPolicy} to {@link MemoryPolicy#defaults()}.
 *
 * <p>{@code outputSchema} (P2-12) is an optional JSON Schema, carried as a raw {@code String}, that
 * constrains the turn's final assistant message: when present the engine parses the reply as JSON and
 * validates it against this schema (a failure aborts the turn with a named error). {@code null} keeps the
 * current free-text behavior, so the field is backward-compatible. It is a {@code String} (not a typed
 * POJO) on purpose: a per-agent output class would force runtime reflection / classpath class loading,
 * breaking the native binary — the schema stays config-driven (CLAUDE.md section 5). A blank-but-present
 * schema is a config error and is rejected.
 */
public record Persona(
    AgentId id,
    String systemPrompt,
    List<String> allowedTools,
    ModelRef primaryModel,
    AgentId parent,
    CostBudget costBudget,
    Long toolBudget,
    String outputSchema,
    List<ModelRef> fallbackModels,
    MemoryPolicy memoryPolicy,
    List<String> roles,
    String identityId
) {
    public Persona {
        if (id == null) {
            throw new IllegalStateException(
                "Persona id must be non-null. Every persona is keyed by its AgentId.");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalStateException(
                "Persona systemPrompt must be non-null and non-blank. Got: '" + systemPrompt
              + "'. Check agents/<id>.md.");
        }
        if (allowedTools == null) {
            throw new IllegalStateException(
                "Persona allowedTools must be non-null (use an empty list for none). Check the "
              + "'allowedTools' field in agents/<id>.json.");
        }
        for (String tool : allowedTools) {
            if (tool == null) {
                throw new IllegalStateException(
                    "Persona allowedTools must not contain null entries. Check the 'allowedTools' "
                  + "array in agents/<id>.json for a stray null element.");
            }
        }
        allowedTools = List.copyOf(allowedTools);
        if (primaryModel == null) {
            throw new IllegalStateException(
                "Persona primaryModel must be non-null. The 'primaryModel' field in agents/<id>.json "
              + "must parse via ModelRef (e.g., \"ollama:qwen3:1.7b\").");
        }
        if (toolBudget != null && toolBudget < 0) {
            throw new IllegalStateException(
                "Persona toolBudget must be non-negative. Got: " + toolBudget
              + ". A negative per-agent tool-loop cap is nonsensical — check agents/<id>.json.");
        }
        if (outputSchema != null && outputSchema.isBlank()) {
            throw new IllegalStateException(
                "Persona outputSchema, when present, must be non-blank. Use null for free-text output, "
              + "or supply a JSON Schema in the 'outputSchema' field of agents/<id>.json.");
        }
        // fallbackModels: null normalizes to empty (primary-only chain); no null entries.
        if (fallbackModels == null) {
            fallbackModels = List.of();
        } else {
            for (ModelRef ref : fallbackModels) {
                if (ref == null) {
                    throw new IllegalStateException(
                        "Persona fallbackModels must not contain null entries. Check the "
                      + "'fallbackModels' array in agents/<id>.json for a stray null element.");
                }
            }
            fallbackModels = List.copyOf(fallbackModels);
        }
        // memoryPolicy: null normalizes to defaults() — the single config-absent source (DR-5 DP-6).
        if (memoryPolicy == null) {
            memoryPolicy = MemoryPolicy.defaults();
        }
        // roles: null normalizes to empty (no agent-level scope cap); no null/blank entries.
        if (roles == null) {
            roles = List.of();
        } else {
            for (String role : roles) {
                if (role == null || role.isBlank()) {
                    throw new IllegalStateException(
                        "Persona roles must not contain null or blank entries. Check the 'roles' "
                      + "array in agents/<id>.json.");
                }
            }
            roles = List.copyOf(roles);
        }
        if (identityId != null && identityId.isBlank()) {
            throw new IllegalStateException(
                "Persona identityId, when present, must be non-blank. Use null for the anonymous "
              + "fallback, or name an identities/<id>.json stem in the 'identityId' field.");
        }
    }

    /**
     * Backward-compatible constructor for a persona predating the DR-8 fields (no fallback chain,
     * memory policy, role cap, or identity pointer). Supplies the "absent" defaults the canonical
     * constructor normalizes to: an empty fallback chain, {@link MemoryPolicy#defaults()}, no role cap,
     * and the anonymous identity fallback. Mirrors an {@code agents/<id>.json} written before those keys
     * existed (the {@code Identity.roles} precedent, P2-11).
     */
    public Persona(AgentId id, String systemPrompt, List<String> allowedTools, ModelRef primaryModel,
            AgentId parent, CostBudget costBudget, Long toolBudget, String outputSchema) {
        this(id, systemPrompt, allowedTools, primaryModel, parent, costBudget, toolBudget, outputSchema,
                List.of(), MemoryPolicy.defaults(), List.of(), null);
    }
}
