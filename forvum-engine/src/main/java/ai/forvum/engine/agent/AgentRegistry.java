package ai.forvum.engine.agent;

import ai.forvum.core.AgentScoped;
import ai.forvum.core.Persona;
import ai.forvum.core.TaskRecord;
import ai.forvum.core.TaskStatus;
import ai.forvum.core.TaskType;
import ai.forvum.core.budget.CostBudget;
import ai.forvum.core.budget.DayWindow;
import ai.forvum.core.budget.SessionWindow;
import ai.forvum.core.budget.SpawnConfigurationException;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.config.AgentReader;
import ai.forvum.engine.config.ConfigurationChangedEvent;
import ai.forvum.engine.context.AgentContext;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.sdk.TaskExecutor;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Application-scoped registry of file-declared agents (ULTRAPLAN section 5.2). Agents live as
 * {@code agents/<id>.md} + {@code <id>.json} under {@code $FORVUM_HOME}; {@link #getOrCreate(AgentId)}
 * lazily loads and validates a spec into a {@link Persona} (via the M4 {@link AgentReader} +
 * {@link AgentSpecReader}) and returns the {@code @AgentScoped} {@link Agent} facade. {@link #spawn} is
 * the programmatic sub-agent path: a distinct child id with a tool belt that must narrow the parent's.
 */
@ApplicationScoped
public class AgentRegistry {

    private static final Logger LOG = Logger.getLogger(AgentRegistry.class);

    @Inject
    AgentReader reader;

    @Inject
    Agent agent;

    @Inject
    TaskExecutor taskExecutor;

    private final AgentSpecReader specReader = new AgentSpecReader();
    private final ConcurrentMap<AgentId, AgentSpec> specs = new ConcurrentHashMap<>();

    /**
     * The live ephemeral-worker ids (#177). {@link #spawn} adds; {@link #retire} removes. This set is the
     * ephemeral membership record: it distinguishes operator-declared persistent agents (loaded via
     * {@link #getOrCreate}, never in this set) from ephemeral worker ids, so {@link #retire} can never
     * unregister a persistent agent, and its size IS the active-worker gauge.
     */
    private final Set<AgentId> ephemeralIds = ConcurrentHashMap.newKeySet();

    /**
     * Resolve the agent, loading its spec from disk on first request. Returns the {@code @AgentScoped}
     * {@link Agent} proxy — invoke its methods inside a {@code CURRENT_AGENT} binding for {@code id}.
     *
     * <p>The blocking file load runs <em>outside</em> the map's compute lock (the read is idempotent, so
     * two concurrent first-resolves at worst both read the file and one loses the {@code putIfAbsent}),
     * keeping disk IO off a {@link ConcurrentHashMap} bin monitor — no carrier-thread pinning.
     */
    public Agent getOrCreate(AgentId id) {
        if (specs.get(id) == null) {
            specs.putIfAbsent(id, load(id));
        }
        return agent;
    }

    /** The registered persona for {@code id}; throws if {@link #getOrCreate}/{@link #spawn} did not register it. */
    public Persona persona(AgentId id) {
        return spec(id).persona();
    }

    /**
     * The full registered {@link AgentSpec} (persona + optional declared cycle) for {@code id}; throws if
     * {@link #getOrCreate}/{@link #spawn} did not register it. The #51 declarative-cycle compiler reads
     * {@code spec(id).cycle()}; most callers want only {@link #persona(AgentId)}.
     */
    public AgentSpec spec(AgentId id) {
        AgentSpec spec = specs.get(id);
        if (spec == null) {
            throw new IllegalStateException(
                    "Agent '" + id.value() + "' is not registered — call getOrCreate(\""
                  + id.value() + "\") first.");
        }
        return spec;
    }

    /**
     * Spawn a sub-agent: a <em>distinct</em> {@code childId} inheriting the parent's system prompt,
     * model, and budgets, with {@code allowedTools} that must be a subset of the parent's (a child can
     * never gain a capability the parent lacks). Registers and returns the child id. The child id must
     * differ from the parent and must not collide with an already-registered agent — spawn never
     * silently overwrites a spec. Inherits the parent's {@code costBudget} verbatim (Decision 10 — an
     * immutable record, and the meter scopes a day window per agent, so the child's spend is tracked
     * independently); use {@link #spawn(AgentId, AgentId, List, CostBudget)} to replace it.
     */
    public AgentId spawn(AgentId parentId, AgentId childId, List<String> allowedTools) {
        return spawn(parentId, childId, allowedTools, null);
    }

    /**
     * As {@link #spawn(AgentId, AgentId, List)} with an explicit {@code costBudget} override for the
     * child (ULTRAPLAN section 4.3.5.2 Decision 10, activated by #169): absent ({@code null}) inherits
     * the parent's budget, present replaces it.
     *
     * @throws SpawnConfigurationException when inheriting would carry a {@code SessionWindow}-scoped
     *         parent budget into the child — that window filters by the PARENT's
     *         {@code (sessionId, agentId)} pair, so the child's own calls would be invisible to its
     *         budget aggregation, leaving it effectively uncapped with no warning
     */
    public AgentId spawn(AgentId parentId, AgentId childId, List<String> allowedTools,
            CostBudget budgetOverride) {
        if (childId.equals(parentId)) {
            throw new IllegalStateException(
                    "spawn: child id '" + childId.value() + "' must differ from its parent.");
        }
        Persona parent = persona(parentId);
        if (!parent.allowedTools().containsAll(allowedTools)) {
            throw new IllegalStateException(
                    "spawn: child '" + childId.value() + "' tool belt " + allowedTools
                  + " must be a subset of parent '" + parentId.value() + "' tool belt "
                  + parent.allowedTools() + ".");
        }
        CostBudget childBudget = budgetOverride != null
                ? budgetOverride
                : inheritedBudget(parentId, childId, parent.costBudget());
        // The child inherits the parent's fallback chain, memory policy, role cap, and identity pointer
        // verbatim (like its system prompt/model/budgets) — it can never gain anything the parent lacks.
        // It does NOT inherit the parent's output schema (P2-12: a worker's output is a digest merged
        // back as a tool result, never the top-level final answer the SupervisorGraph validates) nor a
        // declared cycle (a worker runs a single direct generation — DefaultWorkerRunner, M18).
        Persona child = new Persona(childId, parent.systemPrompt(), allowedTools,
                parent.primaryModel(), parentId, childBudget, parent.toolBudget(), null,
                parent.fallbackModels(), parent.memoryPolicy(), parent.roles(), parent.identityId());
        if (specs.putIfAbsent(childId, new AgentSpec(child, null)) != null) {
            throw new IllegalStateException(
                    "spawn: agent id '" + childId.value() + "' is already registered; choose a distinct "
                  + "child id (spawn never overwrites an existing agent).");
        }
        // Every spawn is an ephemeral worker (retire-eligible); persistent agents enter only via getOrCreate.
        ephemeralIds.add(childId);
        recordSpawnTask(parentId, childId);
        return childId;
    }

    /**
     * Retire a spawned worker once its turn is over (#177): unregister its spec and destroy its
     * {@code @AgentScoped} context, releasing the registry entry and any scoped beans so a long-running
     * server stays bounded. Idempotent and safe by construction — it acts only on ids this registry
     * {@link #spawn}ed (tracked in {@link #ephemeralIds}); an unknown id, an already-retired id, or a
     * persistent file-declared agent id is a no-op, so the ephemeral cleanup path can never tear down a
     * persistent agent. The task/ledger row written at spawn is untouched, so history stays queryable.
     *
     * <p>The spec is removed first (the worker is immediately unregistered), then its {@code @AgentScoped}
     * beans are destroyed. Teardown is not wrapped here — a failure propagates to the caller
     * ({@code SupervisorGraph.retireWorkers}), which is the one place a cleanup failure must be swallowed
     * (in the turn's {@code finally}) and counted so it cannot mask the turn result.
     */
    public void retire(AgentId childId) {
        if (!ephemeralIds.remove(childId)) {
            return; // not a live ephemeral worker — never touch a persistent agent or double-retire
        }
        specs.remove(childId);
        destroyScope(childId);
    }

    /** Live ephemeral worker sub-agents (bounded-observability gauge, #177). */
    public int activeWorkerCount() {
        return ephemeralIds.size();
    }

    /**
     * Destroy every {@code @AgentScoped} bean held for {@code agentId} via the ArC-registered
     * {@link AgentContext}. The teardown runs with {@code CURRENT_AGENT} rebound to {@code agentId} so any
     * {@code @PreDestroy} callback a scoped bean fires resolves in the CHILD's context, not the parent
     * turn's (retirement runs on the parent's turn thread).
     *
     * <p>{@code Arc.container().getContexts(AgentScoped.class)} returns the registered context instances —
     * a deterministic, reflection-free runtime lookup (no dynamic proxy), so it behaves identically on the
     * JVM and in a native image (unlike a ServiceLoader/serialization path). We nonetheless assert the
     * instance IS our {@link AgentContext} and warn loudly if it is not, rather than silently skipping
     * teardown, so a future ArC change that wrapped the context could never let the leak return unnoticed.
     */
    private static void destroyScope(AgentId agentId) {
        for (InjectableContext context : Arc.container().getContexts(AgentScoped.class)) {
            if (context instanceof AgentContext agentContext) {
                ScopedValue.where(CurrentAgent.CURRENT_AGENT, agentId).run(() -> agentContext.destroy(agentId));
                return;
            }
        }
        LOG.warnf("No AgentContext registered for @AgentScoped; cannot destroy scoped beans for retired "
                + "worker '%s' (unregistered, but its @AgentScoped context may leak).", agentId.value());
    }

    /**
     * The Decision-10 spawn guard: a {@link DayWindow} (or absent) parent budget inherits verbatim —
     * the per-agent meter scoping keeps the child's spend independent — but a {@link SessionWindow}
     * budget must not, since its {@code (sessionId, agentId)} pair points at the PARENT and the child
     * would appear to have unlimited budget. Surfaced at spawn time, not silently at runtime.
     */
    private static CostBudget inheritedBudget(AgentId parentId, AgentId childId, CostBudget parentBudget) {
        if (parentBudget != null && parentBudget.window() instanceof SessionWindow) {
            throw new SpawnConfigurationException(parentId.value(), childId.value(),
                    "spawn: parent '" + parentId.value() + "' declares a SessionWindow-scoped costBudget, "
                  + "which filters by the parent's own (sessionId, agentId) pair — inheriting it verbatim "
                  + "would make child '" + childId.value() + "' invisible to its own budget aggregation "
                  + "(effectively uncapped). Pass an explicit CostBudget override to spawn "
                  + "(ULTRAPLAN section 4.3.5.2, Decision 10).");
        }
        return parentBudget;
    }

    /**
     * Write one {@code SUB_AGENT} row to the {@code tasks} ledger after a successful spawn
     * (persist-after-success — a rejected spawn throws before this). A recorder failure must not undo a
     * spawn that already succeeded, so it is logged, never propagated. {@code agentId} is the parent (the
     * agent that initiated the work); {@code subAgentId} is the spawned child. The spawn itself is
     * instantaneous, so the row lands terminal {@code COMPLETED}.
     */
    private void recordSpawnTask(AgentId parentId, AgentId childId) {
        long now = System.currentTimeMillis();
        try {
            taskExecutor.record(new TaskRecord(
                    UUID.randomUUID().toString(), parentId, TaskType.SUB_AGENT, null, childId.value(),
                    "spawn:" + childId.value(), now, now, now, TaskStatus.COMPLETED, null, null, 0L, now));
        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to record tasks-ledger row for spawn of '%s'", childId.value());
        }
    }

    /**
     * Hot reload: on a change to an {@code agents/<id>.md} or {@code <id>.json} file, evict the affected
     * agent's cached spec so the next {@link #getOrCreate} re-reads it from disk (the "watches that
     * directory" half of section 5.2).
     *
     * <p>LIMITATION (deferred to the channel milestones M15–M17): eviction is <em>not</em> safe against
     * a turn already in flight for that agent on another virtual thread — a concurrent {@link #persona}
     * read after the evict would miss and throw. The section 5.2 contract (in-flight turns finish on the
     * OLD spec; the agent's {@code @AgentScoped} instances are torn down via
     * {@code AgentContext.destroy(AgentId)} on reload) needs a per-turn spec snapshot + drain, which
     * lands when channels first drive concurrent turns. M7 has no production turn caller, so this is
     * latent today.
     */
    void onConfigChange(@Observes ConfigurationChangedEvent event) {
        Path path = event.path();
        if (path.getNameCount() < 1 || !"agents".equals(path.getName(0).toString())) {
            return;
        }
        String fileName = path.getFileName().toString();
        if (!fileName.endsWith(".md") && !fileName.endsWith(".json")) {
            return; // ignore stray entries (dotfiles, editor temp files) — only agent files map to an id
        }
        String idValue = fileName.substring(0, fileName.lastIndexOf('.'));
        try {
            specs.remove(new AgentId(idValue));
        } catch (IllegalStateException ignored) {
            // A malformed stem (e.g. a file literally named ".json") is not a valid agent id.
        }
    }

    private AgentSpec load(AgentId id) {
        String persona = reader.persona(id.value()).orElseThrow(() -> missingFile(id, "md"));
        JsonNode spec = reader.spec(id.value()).orElseThrow(() -> missingFile(id, "json"));
        return specReader.parseSpec(id, persona, spec);
    }

    private static IllegalStateException missingFile(AgentId id, String ext) {
        return new IllegalStateException(
                "Agent '" + id.value() + "' cannot be activated: its ." + ext + " file is missing. "
              + "Both agents/" + id.value() + ".md and agents/" + id.value() + ".json are required.");
    }
}
