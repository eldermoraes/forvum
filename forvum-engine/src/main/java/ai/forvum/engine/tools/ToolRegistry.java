package ai.forvum.engine.tools;

import ai.forvum.core.ToolSpec;
import ai.forvum.engine.config.ConfigurationChangedEvent;
import ai.forvum.engine.runtime.CommandMode;
import ai.forvum.sdk.ToolProvider;

import io.quarkus.runtime.StartupEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Application-scoped registry of every {@link ToolSpec} contributed by every {@link ToolProvider} plugin
 * (ULTRAPLAN section 5.3). Extension-agnostic: providers are discovered via CDI and their tools indexed
 * by name at startup, so the engine never names a concrete tool module. The registry is the global set
 * that {@link ToolFilter} narrows into each agent's {@code AgentToolBelt}; the LLM only ever sees that
 * filtered subset.
 *
 * <p>Tool names are globally unique across providers — a duplicate name is a hard configuration error,
 * never a silent overwrite (the same {@code putIfAbsent}-and-throw guard as {@code AgentRegistry.spawn}).
 *
 * <p><strong>Atomic publication (P2-13).</strong> The {@code (tools, owners)} pair is held in a single
 * {@code volatile} {@link Index} so every reader ({@link #all()}/{@link #lookup}/{@link #providerFor})
 * observes a fully-consistent snapshot — never a half-rebuilt map. A resync swaps ONE reference; there is
 * no {@code clear()}+{@code putAll()} window in which a concurrent turn could miss a stable tool (and
 * {@code ToolCallBridge} would then throw belt/registry-divergence).
 *
 * <p><strong>One-shot gate (P2-13 / M20).</strong> Materializing the MCP bridge's tools is now a blocking
 * network round-trip (connect + {@code listTools}), so {@link #onStart} is no longer the "cheap,
 * side-effect-free" observer the M20 cold-start lever could leave ungated: it SKIPS materialization for a
 * one-shot command (so {@code --help}/{@code --version}/{@code init}/{@code doctor}/{@code plugin}/
 * {@code skill}/{@code mcp add} stay instant and offline even on a machine with configured MCP servers).
 * {@code mcp list} re-materializes on demand by calling the bridge directly. A normal (server) run
 * materializes here as before.
 */
@ApplicationScoped
public class ToolRegistry {

    /** A consistent snapshot of the registered tools and their owning providers, published atomically. */
    private record Index(Map<String, ToolSpec> tools, Map<String, ToolProvider> owners) {
    }

    @Inject
    Instance<ToolProvider> providers;

    @Inject
    CommandMode commandMode;

    private volatile Index index = new Index(Map.of(), Map.of());

    /** Index every discovered provider's tools at startup, so the belt is ready before the first turn. */
    void onStart(@Observes StartupEvent event) {
        if (commandMode != null && commandMode.isOneShot()) {
            // A one-shot command runs no turn, so it needs no belt — and skipping materialization keeps the
            // MCP bridge's boot-time connect (a blocking network round-trip) off the cold-start path even
            // when MCP servers are configured (the M20 lever; P2-13). `mcp list` re-materializes on demand.
            return;
        }
        rebuildFromProviders();
    }

    /**
     * Register every tool a provider contributes, rejecting a name already claimed by another provider.
     * Package-private so tests register synthetic providers directly without booting CDI. Rebuilds the
     * published {@link Index} atomically onto the current snapshot.
     */
    void register(ToolProvider provider) {
        Map<String, ToolSpec> tools = new HashMap<>(index.tools());
        Map<String, ToolProvider> owners = new HashMap<>(index.owners());
        indexProvider(provider, tools, owners);
        publish(tools, owners);
    }

    /**
     * Re-materialize the registry when the MCP server registry changes (P2-13): an
     * {@code mcp-servers/<id>.json} add/remove/edit changes the {@code mcp.<server>.*} tool set, and the
     * unique-name guard means we must REBUILD rather than re-add. The provider self-reads its config in
     * {@code tools()}, so re-calling every provider picks up the new MCP set (and re-yields the static
     * tools idempotently); a withdrawn server's specs simply do not reappear. The rebuild happens in temp
     * maps — where the MCP connect/list IO occurs — then a SINGLE atomic {@code volatile} swap of the
     * {@link Index} publishes it, so a concurrent {@code lookup}/{@code providerFor} sees the complete old
     * or complete new snapshot, never a partial one (CLAUDE.md §3.8 — no {@code synchronized}). Other
     * config subfolders are ignored (they do not change the tool set).
     */
    void onConfigChange(@Observes ConfigurationChangedEvent event) {
        Path path = event.path();
        if (path == null || path.getNameCount() < 1
                || !"mcp-servers".equals(path.getName(0).toString())) {
            return;
        }
        rebuildFromProviders();
    }

    /** Rebuild the index from every discovered provider and publish it atomically. */
    private void rebuildFromProviders() {
        Map<String, ToolSpec> tools = new HashMap<>();
        Map<String, ToolProvider> owners = new HashMap<>();
        for (ToolProvider provider : providers) {
            indexProvider(provider, tools, owners);
        }
        publish(tools, owners);
    }

    /** Publish an immutable snapshot in one volatile write (atomic for all readers). */
    private void publish(Map<String, ToolSpec> tools, Map<String, ToolProvider> owners) {
        this.index = new Index(Map.copyOf(tools), Map.copyOf(owners));
    }

    /** Index a provider's tools into {@code toolsMap}/{@code ownersMap}, enforcing globally-unique names. */
    private static void indexProvider(ToolProvider provider, Map<String, ToolSpec> toolsMap,
                                      Map<String, ToolProvider> ownersMap) {
        for (ToolSpec spec : provider.tools()) {
            ToolSpec existing = toolsMap.putIfAbsent(spec.name(), spec);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate tool name '" + spec.name() + "' contributed by extension '"
                      + provider.extensionId() + "'. Tool names must be globally unique across providers "
                      + "(it is already registered). Rename one of the colliding tools.");
            }
            ownersMap.put(spec.name(), provider);
        }
    }

    /** An immutable snapshot of every registered tool. */
    public List<ToolSpec> all() {
        return List.copyOf(index.tools().values());
    }

    /** The registered spec for {@code name}, or {@code null} if no provider contributes it. */
    public ToolSpec lookup(String name) {
        return index.tools().get(name);
    }

    /**
     * The provider that contributes {@code name}, or {@code null} if none does — the M18 {@code tool_loop}
     * routes a model-emitted call to its owning provider's {@code invoke(...)} through this (ULTRAPLAN
     * section 5.5). Names are globally unique (see {@link #register}), so the mapping is unambiguous.
     */
    public ToolProvider providerFor(String name) {
        return index.owners().get(name);
    }
}
