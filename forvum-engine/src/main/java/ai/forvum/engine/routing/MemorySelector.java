package ai.forvum.engine.routing;

import ai.forvum.core.MemoryHit;
import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.MemoryQuery;
import ai.forvum.core.RetrievalStrategy;
import ai.forvum.sdk.MemoryProvider;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.util.List;

/**
 * The host side of the {@link MemoryProvider} SPI: it selects the installed provider and runs the
 * Context-Engineering Select pillar's retrieval for a turn (DR-5, ULTRAPLAN §4.3.6). Mirrors
 * {@link LlmSelector}'s {@code Instance<...>} CDI discovery.
 *
 * <p>v0.1 resolves the SINGLE installed memory provider — DR-5 names no per-policy provider key, so
 * multi-provider selection is a documented deferral (a second installed provider is ignored). Retrieval
 * is a no-op (an empty list) when there is no provider, when {@code policy.strategy() == NONE}, or when the
 * provider fails: a retrieval problem must NEVER fail the turn (graceful degradation), so a provider
 * exception is logged and swallowed.
 */
@ApplicationScoped
public class MemorySelector {

    private static final Logger LOG = Logger.getLogger(MemorySelector.class);

    @Inject
    Instance<MemoryProvider> providers;

    /**
     * Retrieve the memory relevant to {@code query} under {@code policy}, or an empty list when retrieval
     * is disabled (null / {@code NONE} policy), no provider is installed, or the provider throws. Never
     * returns {@code null}.
     */
    public List<MemoryHit> retrieve(MemoryQuery query, MemoryPolicy policy) {
        if (policy == null || policy.strategy() == RetrievalStrategy.NONE) {
            return List.of();
        }
        MemoryProvider provider = firstProvider();
        if (provider == null) {
            return List.of();
        }
        try {
            List<MemoryHit> hits = provider.retrieve(query, policy);
            return hits == null ? List.of() : hits;
        } catch (RuntimeException e) {
            LOG.warnf(e, "Memory retrieval via '%s' failed for agent '%s'; continuing without retrieved "
                    + "memory.", provider.extensionId(), query.agentId());
            return List.of();
        }
    }

    /** The single installed memory provider, or {@code null} when none is installed. Overridable for tests. */
    MemoryProvider firstProvider() {
        for (MemoryProvider provider : providers) {
            return provider;
        }
        return null;
    }
}
