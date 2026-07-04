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
import ai.forvum.engine.config.ChangeType;
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
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * The per-turn agent snapshot lease (#178). Bound once at each turn entry ({@code TurnService.dispatch},
     * {@code CronScheduler.fire}) to the {@link LiveAgent} generation the turn runs on; {@link #persona}/
     * {@link #spec} return it (enforce-iff-bound — the P2-11 {@code CURRENT_EFFECTIVE_SCOPES} pattern) so
     * every read for that turn observes one consistent generation even as {@link #onConfigChange} publishes
     * newer ones. It lives here rather than on {@code CurrentAgent} because {@link LiveAgent} is in this
     * package and a {@code context -> agent} import would create a package cycle; the registry is the
     * lease's producer and consumer. A worker virtual thread does not inherit it ({@code ScopedValue}
     * semantics), so a worker reads its own ephemeral child spec from the map — correct.
     */
    public static final ScopedValue<LiveAgent> CURRENT_AGENT_SPEC = ScopedValue.newInstance();

    @Inject
    AgentReader reader;

    @Inject
    Agent agent;

    @Inject
    TaskExecutor taskExecutor;

    private final AgentSpecReader specReader = new AgentSpecReader();

    /** Monotonic generation stamps — never reused, so a delete+recreate of an id is ABA-distinguishable (#178). */
    private final AtomicLong nextGeneration = new AtomicLong();

    /** Count of hot-reload rebuilds that failed validation and kept the last-known-good spec (#178). */
    private final AtomicLong reloadFailures = new AtomicLong();

    private final ConcurrentMap<AgentId, LiveAgent> specs = new ConcurrentHashMap<>();

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
            specs.putIfAbsent(id, new LiveAgent(nextGeneration.incrementAndGet(), load(id)));
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
        return live(id).spec();
    }

    /**
     * The current registered {@link LiveAgent} generation for {@code id}, to be bound into
     * {@link #CURRENT_AGENT_SPEC} at a turn entry so the whole turn reads one frozen generation (#178).
     * Throws if {@code id} is not registered (a deleted or never-loaded agent) — the caller surfaces that
     * as a terminal turn error (rejected new-turn).
     */
    public LiveAgent lease(AgentId id) {
        return live(id);
    }

    /**
     * The generation a read resolves to: the per-turn leased snapshot when {@link #CURRENT_AGENT_SPEC} is
     * bound for {@code id} (so an in-flight turn is immune to a concurrent reload), else the current map
     * entry. Throws if unregistered.
     */
    private LiveAgent live(AgentId id) {
        if (CURRENT_AGENT_SPEC.isBound()) {
            LiveAgent leased = CURRENT_AGENT_SPEC.get();
            if (leased.persona().id().equals(id)) {
                return leased; // the turn's frozen generation — immune to a concurrent reload
            }
        }
        LiveAgent found = specs.get(id);
        if (found == null) {
            throw new IllegalStateException(
                    "Agent '" + id.value() + "' is not registered — call getOrCreate(\""
                  + id.value() + "\") first.");
        }
        return found;
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
        if (specs.putIfAbsent(childId, new LiveAgent(nextGeneration.incrementAndGet(),
                new AgentSpec(child, null))) != null) {
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

    /** Count of hot-reload rebuilds that failed validation and kept the last-known-good spec (#178). */
    public long reloadFailureCount() {
        return reloadFailures.get();
    }

    /** The current registered generation for {@code id}, or {@code -1} if unregistered (#178, test/observability). */
    public long generation(AgentId id) {
        LiveAgent found = specs.get(id);
        return found == null ? -1L : found.generation();
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
     * Hot reload (#178): a change to {@code agents/<id>.md} or {@code <id>.json} rebuilds the agent's FULL
     * spec and publishes it atomically for NEW turns, while in-flight turns keep running on the generation
     * they leased (they hold an immutable {@link LiveAgent} via {@link #CURRENT_AGENT_SPEC}, so a swap here
     * cannot disrupt them). The publish is a single {@code specs.replace} — the {@code ToolRegistry}
     * volatile-swap precedent — so a reader sees the whole old or the whole new generation, never a mix.
     *
     * <p>Only a currently-registered (in-use) agent is reloaded; an unloaded agent stays lazy (the next
     * {@link #getOrCreate} reads it fresh). On {@code DELETED} the entry is removed and its
     * {@code @AgentScoped} beans destroyed, so a new turn's {@link #lease} throws (rejected new-turn) while
     * in-flight turns finish on their lease. A rebuild that fails validation (a malformed or half-written
     * file, or a still-incomplete {@code .md}/{@code .json} pair) is dropped: the current known-good
     * generation is retained and the failure counted — capabilities are NEVER temporarily widened. The two
     * files arrive as two separate events; rebuilding the full spec on each is safe (every published
     * generation is a complete, validated spec — never a mix of fields across generations). The blocking
     * file read runs OUTSIDE any map compute lock ([M7] — no carrier-thread pinning).
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
        AgentId id;
        try {
            id = new AgentId(fileName.substring(0, fileName.lastIndexOf('.')));
        } catch (IllegalStateException notAnId) {
            return; // a malformed stem (e.g. a file literally named ".json") is not a valid agent id
        }
        if (specs.get(id) == null) {
            return; // not in use — the next getOrCreate loads it fresh (agents stay lazy)
        }
        if (event.type() == ChangeType.DELETED) {
            if (specs.remove(id) != null) {
                destroyScope(id);
                LOG.infof("Hot reload: agent '%s' deleted — unregistered; new turns are rejected.",
                        id.value());
            }
            return;
        }
        // CREATED/MODIFIED on an in-use agent: rebuild the FULL spec OFF the lock, then swap atomically.
        AgentSpec rebuilt;
        try {
            rebuilt = load(id);
        } catch (RuntimeException invalid) {
            reloadFailures.incrementAndGet();
            LOG.warnf("Hot reload: agent '%s' change rejected (%s) — keeping the last known-good "
                    + "configuration; capabilities unchanged.", id.value(),
                    invalid.getClass().getSimpleName());
            return;
        }
        long gen = nextGeneration.incrementAndGet();
        LiveAgent previous = specs.replace(id, new LiveAgent(gen, rebuilt));
        if (previous != null) {
            destroyScope(id); // hygiene: drop any @AgentScoped state derived from the old generation
            LOG.infof("Hot reload: agent '%s' republished at generation %d.", id.value(), gen);
        }
        // previous == null: the agent was deleted concurrently between the get and the replace — do not
        // resurrect it (the burned generation number is harmless; stamps are monotonic, never reused).
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
