package ai.forvum.core.id;

/**
 * Stable identifier for an agent, matching the {@code agents/<id>.json} filename and the
 * {@code agent_id} column across the SQLite schema (ULTRAPLAN section 4.2). Propagated per-turn via
 * {@code ScopedValue<AgentId>} in the {@code @AgentScoped} context (section 5.1).
 */
public record AgentId(String value) {
    public AgentId {
        if (value == null || value.isBlank() || !value.strip().equals(value)) {
            throw new IllegalStateException(
                "AgentId value must be a non-blank token without leading/trailing "
              + "whitespace. Got: '" + value + "'. Check agents/<id>.json filename.");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
