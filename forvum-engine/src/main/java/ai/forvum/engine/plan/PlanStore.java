package ai.forvum.engine.plan;

import java.util.Optional;

/**
 * The persistence seam for the {@code update_plan} session plan scratchpad (#190): append-only
 * newest-wins plan rows in {@code messages} ({@code role = tool}, {@code block_type = plan}). An
 * interface (the M13 store-seam recipe) so the graph unit tests substitute an in-memory double; the
 * production implementor is {@link PanachePlanStore}.
 *
 * <p>Never update/delete in place — a superseded row may sit inside the frozen compaction prefix
 * (P2-COMPACT prompt-cache stability); supersession is by a newer row, stripping is the compactor's job.
 */
public interface PlanStore {

    /** Append a new plan row for {@code (sessionId, agentId)} — the new live plan. */
    void save(String sessionId, String agentId, String renderedPlan);

    /** The newest (live) rendered plan for {@code (sessionId, agentId)}, or empty when none exists. */
    Optional<String> latest(String sessionId, String agentId);
}
