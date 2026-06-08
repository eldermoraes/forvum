package ai.forvum.engine.agent;

import ai.forvum.core.Persona;
import ai.forvum.core.TaskRecord;
import ai.forvum.core.TaskStatus;
import ai.forvum.core.TaskType;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.config.AgentReader;
import ai.forvum.engine.config.ConfigurationChangedEvent;
import ai.forvum.sdk.TaskExecutor;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.List;
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
    private final ConcurrentMap<AgentId, Persona> specs = new ConcurrentHashMap<>();

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
        Persona persona = specs.get(id);
        if (persona == null) {
            throw new IllegalStateException(
                    "Agent '" + id.value() + "' is not registered — call getOrCreate(\""
                  + id.value() + "\") first.");
        }
        return persona;
    }

    /**
     * Spawn a sub-agent: a <em>distinct</em> {@code childId} inheriting the parent's system prompt,
     * model, and budgets, with {@code allowedTools} that must be a subset of the parent's (a child can
     * never gain a capability the parent lacks). Registers and returns the child id. The child id must
     * differ from the parent and must not collide with an already-registered agent — spawn never
     * silently overwrites a spec.
     *
     * <p>TODO (ULTRAPLAN section 4.3.5.2, Decision 10): when cost-budget parsing lands, add an optional
     * {@code CostBudget} override parameter (absent ⇒ inherit, present ⇒ replace) and throw
     * {@code SpawnConfigurationException} on inheriting a {@code SessionWindow}-scoped parent budget
     * without an override. Inert today — {@link AgentSpecReader} leaves {@code costBudget} null.
     */
    public AgentId spawn(AgentId parentId, AgentId childId, List<String> allowedTools) {
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
        // A worker's output is a digest merged back as a tool result (never the top-level final answer
        // the SupervisorGraph validates), so the child does NOT inherit the parent's output schema (P2-12).
        Persona child = new Persona(childId, parent.systemPrompt(), allowedTools,
                parent.primaryModel(), parentId, parent.costBudget(), parent.toolBudget(), null);
        if (specs.putIfAbsent(childId, child) != null) {
            throw new IllegalStateException(
                    "spawn: agent id '" + childId.value() + "' is already registered; choose a distinct "
                  + "child id (spawn never overwrites an existing agent).");
        }
        recordSpawnTask(parentId, childId);
        return childId;
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

    private Persona load(AgentId id) {
        String persona = reader.persona(id.value()).orElseThrow(() -> missingFile(id, "md"));
        JsonNode spec = reader.spec(id.value()).orElseThrow(() -> missingFile(id, "json"));
        return specReader.parse(id, persona, spec);
    }

    private static IllegalStateException missingFile(AgentId id, String ext) {
        return new IllegalStateException(
                "Agent '" + id.value() + "' cannot be activated: its ." + ext + " file is missing. "
              + "Both agents/" + id.value() + ".md and agents/" + id.value() + ".json are required.");
    }
}
