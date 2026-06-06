package ai.forvum.engine.tools;

import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.ToolProvider;

import io.quarkus.runtime.StartupEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Application-scoped registry of every {@link ToolSpec} contributed by every {@link ToolProvider} plugin
 * (ULTRAPLAN section 5.3). Extension-agnostic: providers are discovered via CDI and their tools indexed
 * by name at startup, so the engine never names a concrete tool module. The registry is the global set
 * that {@link ToolFilter} narrows into each agent's {@code AgentToolBelt}; the LLM only ever sees that
 * filtered subset.
 *
 * <p>Tool names are globally unique across providers — a duplicate name is a hard configuration error,
 * never a silent overwrite (the same {@code putIfAbsent}-and-throw guard as {@code AgentRegistry.spawn}).
 */
@ApplicationScoped
public class ToolRegistry {

    @Inject
    Instance<ToolProvider> providers;

    private final ConcurrentMap<String, ToolSpec> tools = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ToolProvider> providerByTool = new ConcurrentHashMap<>();

    /** Index every discovered provider's tools at startup, so the belt is ready before the first turn. */
    void onStart(@Observes StartupEvent event) {
        for (ToolProvider provider : providers) {
            register(provider);
        }
    }

    /**
     * Register every tool a provider contributes, rejecting a name already claimed by another provider.
     * Package-private so tests register synthetic providers directly without booting CDI.
     */
    void register(ToolProvider provider) {
        for (ToolSpec spec : provider.tools()) {
            ToolSpec existing = tools.putIfAbsent(spec.name(), spec);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate tool name '" + spec.name() + "' contributed by extension '"
                      + provider.extensionId() + "'. Tool names must be globally unique across providers "
                      + "(it is already registered). Rename one of the colliding tools.");
            }
            providerByTool.put(spec.name(), provider);
        }
    }

    /** An immutable snapshot of every registered tool. */
    public List<ToolSpec> all() {
        return List.copyOf(tools.values());
    }

    /** The registered spec for {@code name}, or {@code null} if no provider contributes it. */
    public ToolSpec lookup(String name) {
        return tools.get(name);
    }

    /**
     * The provider that contributes {@code name}, or {@code null} if none does — the M18 {@code tool_loop}
     * routes a model-emitted call to its owning provider's {@code invoke(...)} through this (ULTRAPLAN
     * section 5.5). Names are globally unique (see {@link #register}), so the mapping is unambiguous.
     */
    public ToolProvider providerFor(String name) {
        return providerByTool.get(name);
    }
}
