package ai.forvum.engine.context;

import io.quarkus.arc.InjectableBean;
import io.quarkus.arc.InjectableContext;

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import ai.forvum.core.AgentScoped;
import ai.forvum.core.id.AgentId;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ArC {@link InjectableContext} backing {@link AgentScoped}. Bean instances are isolated per
 * {@link AgentId}: each agent gets its own map of contextual instances, keyed by the
 * {@link CurrentAgent#CURRENT_AGENT} {@link ScopedValue} bound on the calling (virtual) thread.
 *
 * <p>No {@code synchronized} (ULTRAPLAN section 3.8) — the per-agent and per-contextual maps are
 * {@link ConcurrentHashMap}s. {@link #isActive()} is true only inside a {@code CURRENT_AGENT} binding.
 */
public final class AgentContext implements InjectableContext {

    private record Handle(Object instance, CreationalContext<?> creationalContext) {
    }

    private final Map<AgentId, Map<Contextual<?>, Handle>> store = new ConcurrentHashMap<>();

    @Override
    public Class<? extends Annotation> getScope() {
        return AgentScoped.class;
    }

    @Override
    public boolean isActive() {
        return CurrentAgent.CURRENT_AGENT.isBound();
    }

    @Override
    public boolean isNormal() {
        return true;
    }

    private Map<Contextual<?>, Handle> currentMap() {
        if (!CurrentAgent.CURRENT_AGENT.isBound()) {
            throw new ContextNotActiveException(
                    "@AgentScoped bean accessed with no CURRENT_AGENT bound — wrap the call in "
                  + "ScopedValue.where(CurrentAgent.CURRENT_AGENT, agentId).call(...)");
        }
        return store.computeIfAbsent(CurrentAgent.CURRENT_AGENT.get(), k -> new ConcurrentHashMap<>());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        Map<Contextual<?>, Handle> map = currentMap();
        if (creationalContext == null) {
            Handle existing = map.get(contextual);
            return existing != null ? (T) existing.instance() : null;
        }
        // Atomic per (agent, contextual): when the same agent is bound on two virtual threads that
        // first-resolve the same bean concurrently, exactly one instance is created — a plain
        // get-then-put would create two and leak the loser's CreationalContext.
        Handle handle = map.computeIfAbsent(contextual,
                c -> new Handle(contextual.create(creationalContext), creationalContext));
        return (T) handle.instance();
    }

    @Override
    public <T> T get(Contextual<T> contextual) {
        return get(contextual, null);
    }

    @Override
    public void destroy(Contextual<?> contextual) {
        if (!CurrentAgent.CURRENT_AGENT.isBound()) {
            return;
        }
        Map<Contextual<?>, Handle> map = store.get(CurrentAgent.CURRENT_AGENT.get());
        if (map == null) {
            return;
        }
        destroyHandle(contextual, map.remove(contextual));
    }

    /**
     * Destroys the currently bound agent's beans. Required by the {@code InjectableContext} SPI, but
     * ArC generates a wrapper that overrides the no-arg {@code destroy()}/{@code getState()} with
     * throwing stubs (a custom {@code AlterableContext} is not asked to tear down "all" instances), so
     * this body is not exercised by the container-shutdown lifecycle — per-agent teardown goes through
     * {@link #destroy(AgentId)} (M7). Kept functional and harmless in case it is ever invoked directly.
     */
    @Override
    public void destroy() {
        if (!CurrentAgent.CURRENT_AGENT.isBound()) {
            return;
        }
        destroyAll(store.remove(CurrentAgent.CURRENT_AGENT.get()));
    }

    /** Evict and destroy every bean held for {@code agentId}. Used by {@code AgentRegistry} (M7). */
    public void destroy(AgentId agentId) {
        destroyAll(store.remove(agentId));
    }

    private static void destroyAll(Map<Contextual<?>, Handle> map) {
        if (map == null) {
            return;
        }
        map.forEach(AgentContext::destroyHandle);
        map.clear();
    }

    @SuppressWarnings("unchecked")
    private static void destroyHandle(Contextual<?> contextual, Handle handle) {
        if (handle != null) {
            ((Contextual<Object>) contextual).destroy(handle.instance(),
                    (CreationalContext<Object>) handle.creationalContext());
        }
    }

    /**
     * Snapshot of the current agent's contextual instances. Required by the SPI; like {@link #destroy()}
     * it is shadowed by ArC's generated wrapper (which throws "has no state"), so it does not drive
     * container shutdown — do not read it as evidence that {@code @AgentScoped} beans are reaped on stop.
     */
    @Override
    public ContextState getState() {
        Map<Contextual<?>, Handle> map = currentMap();
        Map<InjectableBean<?>, Object> instances = new HashMap<>();
        map.forEach((contextual, handle) -> {
            if (contextual instanceof InjectableBean<?> bean) {
                instances.put(bean, handle.instance());
            }
        });
        return () -> instances;
    }
}
