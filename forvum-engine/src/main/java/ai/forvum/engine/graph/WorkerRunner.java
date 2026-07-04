package ai.forvum.engine.graph;

import ai.forvum.core.id.AgentId;

import java.util.List;

/**
 * The seam the supervisor's {@code spawn_worker}/{@code worker_run} nodes use to materialize and drive a
 * sub-agent (ULTRAPLAN section 5.5). Kept as an interface so the {@link SupervisorGraph} stays decoupled
 * from {@code AgentRegistry}/{@code LlmSelector} and is testable with a fake; {@link DefaultWorkerRunner}
 * is the real implementation. The Orchestrator-Workers Isolate boundary is here: a worker runs in its own
 * {@code @AgentScoped} context and only its (compressed) digest crosses back to the parent.
 */
public interface WorkerRunner {

    /**
     * Materialize a worker sub-agent: register {@code childId} as a child of {@code parentId} with the
     * narrowed {@code allowedTools} belt (delegates to {@code AgentRegistry.spawn}). Throws if the child
     * id collides with an existing agent or equals the parent.
     */
    void spawn(AgentId parentId, AgentId childId, List<String> allowedTools);

    /**
     * Drive {@code childId}'s turn for {@code task} (bound to that agent's {@code @AgentScoped} context)
     * and return its final message — the worker's raw digest before the {@code reduce} compression. Called
     * once per worker; the {@code worker_run} node runs many concurrently on virtual threads.
     */
    String runWorker(AgentId childId, String task, String sessionId);

    /**
     * Retire a spawned worker after its turn is over (#177): unregister it and destroy its
     * {@code @AgentScoped} context, so a long-running server does not accumulate registry entries or
     * scoped beans. Called for every worker the turn materialized, on <em>every</em> exit path (success,
     * model/tool failure, timeout, budget exhaustion, supervisor interruption) — the supervisor drives it
     * only after {@code worker_run} has joined all fan-out, so no worker-owned async work is still live.
     * Best-effort and idempotent: it never throws for a persistent agent or an already-retired id, and a
     * cleanup failure must not mask the turn's own result (delegates to {@code AgentRegistry.retire}).
     * Task/ledger/audit history for the worker survives retirement.
     */
    void retire(AgentId childId);
}
