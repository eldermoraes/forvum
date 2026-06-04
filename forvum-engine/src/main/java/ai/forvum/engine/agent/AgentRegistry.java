package ai.forvum.engine.agent;

import ai.forvum.core.Persona;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.config.AgentReader;
import ai.forvum.engine.config.ConfigurationChangedEvent;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Application-scoped registry of file-declared agents (ULTRAPLAN section 5.2). Agents live as
 * {@code agents/<id>.md} + {@code <id>.json} under {@code $FORVUM_HOME}; {@link #getOrCreate(AgentId)}
 * lazily loads and validates a spec into a {@link Persona} (via the M4 {@link AgentReader} +
 * {@link AgentSpecReader}) and returns the {@code @AgentScoped} {@link Agent} facade. {@link #spawn} is
 * the programmatic sub-agent path: a child with a distinct id and a tool belt that must narrow the
 * parent's.
 */
@ApplicationScoped
public class AgentRegistry {

    @Inject
    AgentReader reader;

    @Inject
    Agent agent;

    private final AgentSpecReader specReader = new AgentSpecReader();
    private final ConcurrentMap<AgentId, Persona> specs = new ConcurrentHashMap<>();

    /**
     * Resolve the agent, loading its spec from disk on first request. Returns the {@code @AgentScoped}
     * {@link Agent} proxy — invoke its methods inside a {@code CURRENT_AGENT} binding for {@code id}.
     */
    public Agent getOrCreate(AgentId id) {
        specs.computeIfAbsent(id, this::load);
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
     * Spawn a sub-agent: a distinct {@code childId} inheriting the parent's system prompt, model, and
     * budgets, with {@code allowedTools} that must be a subset of the parent's (a child can never gain
     * a capability the parent lacks). Registers and returns the child id.
     */
    public AgentId spawn(AgentId parentId, AgentId childId, List<String> allowedTools) {
        Persona parent = persona(parentId);
        if (!parent.allowedTools().containsAll(allowedTools)) {
            throw new IllegalStateException(
                    "spawn: child '" + childId.value() + "' tool belt " + allowedTools
                  + " must be a subset of parent '" + parentId.value() + "' tool belt "
                  + parent.allowedTools() + ".");
        }
        Persona child = new Persona(childId, parent.systemPrompt(), allowedTools,
                parent.primaryModel(), parentId, parent.costBudget(), parent.toolBudget());
        specs.put(childId, child);
        return childId;
    }

    /**
     * Hot reload: on any change under {@code agents/}, evict the affected agent's cached spec so the
     * next {@link #getOrCreate} re-reads it from disk (ULTRAPLAN section 5.2 — "watches that
     * directory"). A bound {@link Agent} reads its persona through {@link #persona} on each call, so no
     * stale state survives the eviction.
     */
    void onConfigChange(@Observes ConfigurationChangedEvent event) {
        Path path = event.path();
        if (path.getNameCount() < 1 || !"agents".equals(path.getName(0).toString())) {
            return;
        }
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String idValue = dot > 0 ? fileName.substring(0, dot) : fileName;
        try {
            specs.remove(new AgentId(idValue));
        } catch (IllegalStateException ignored) {
            // Not a valid agent-id token (e.g. a stray dotfile) — nothing to evict.
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
