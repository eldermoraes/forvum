package ai.forvum.core.budget;

/**
 * Thrown at spawn time when a parent agent's
 * {@link CostBudget} carries a {@link SessionWindow} and
 * the spawn request omits an explicit budget override.
 *
 * <p>When a parent's {@code CostBudget} uses
 * {@code SessionWindow}, the window filters by the
 * parent's {@code (sessionId, agentId)} pair — so
 * inheriting it verbatim into a child would cause every
 * call the child makes (tagged with the child's own
 * {@code (sessionId, agentId)}) to be invisible to the
 * budget's SUM aggregation. The child would appear to
 * have unlimited budget. This exception surfaces the
 * misconfiguration at spawn time rather than silently
 * at runtime. See §5.5 for the validation site and the
 * recommended override shape.
 *
 * <p>Like {@link BudgetExhaustedException}, this is
 * unchecked and is caught by the engine layer, which
 * surfaces it as a terminal {@code Error}
 * {@link ai.forvum.core.event.AgentEvent} with
 * {@code code = "spawn_invalid_config"} plus
 * {@code parentAgentId}, {@code childAgentId}, and the
 * educational {@code getMessage()} text.
 */
public final class SpawnConfigurationException extends RuntimeException {
    private final String parentAgentId;
    private final String childAgentId;

    public SpawnConfigurationException(
            String parentAgentId, String childAgentId, String reason) {
        super(reason);
        this.parentAgentId = parentAgentId;
        this.childAgentId = childAgentId;
    }

    public String parentAgentId() { return parentAgentId; }
    public String childAgentId() { return childAgentId; }
}
