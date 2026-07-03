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
 * unchecked and carries {@code parentAgentId},
 * {@code childAgentId}, and the educational
 * {@code getMessage()} text.
 *
 * <p><b>As-built (#169).</b> The spawn guard is activated
 * but is a defensive spawn-time safeguard for the
 * programmatic budget-override path: it is unreachable
 * from file config, since {@code AgentSpecReader} rejects
 * a file-declared {@code "session"} window, so no
 * file-parsed agent can carry a {@link SessionWindow}
 * budget to inherit. In the M18 supervisor graph the sole
 * production spawn path is the {@code spawn_worker}
 * fan-out, which — like every spawn failure (id collision,
 * self-id, belt-widening) — renders this exception as a
 * model-visible tool result and lets the turn continue,
 * rather than a terminal {@code spawn_invalid_config}
 * {@link ai.forvum.core.event.AgentEvent} (the once-designed
 * terminal-error surface has no M18 spawn path that escapes
 * to it).
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
