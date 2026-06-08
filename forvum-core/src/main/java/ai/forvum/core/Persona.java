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
 * <p>The LLM fallback chain (pending {@code FallbackChain}, DR-4c) and the retrieval policy (pending
 * {@code MemoryPolicy}, DR-5) are intentionally omitted until those contracts land; only
 * {@code primaryModel} is carried today.
 *
 * <p>{@code allowedTools} is defensively copied to an immutable list by the canonical constructor.
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
    String outputSchema
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
    }
}
