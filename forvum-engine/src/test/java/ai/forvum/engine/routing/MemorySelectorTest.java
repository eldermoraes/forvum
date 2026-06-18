package ai.forvum.engine.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import ai.forvum.core.MemoryHit;
import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.MemoryQuery;
import ai.forvum.core.MemoryTier;
import ai.forvum.core.RetrievalStrategy;
import ai.forvum.sdk.AbstractMemoryProvider;
import ai.forvum.sdk.MemoryProvider;

/**
 * {@link MemorySelector} host logic: skip on null/NONE policy, no-op with no provider, delegate to the
 * single installed provider, and degrade gracefully (empty, never throws) on a provider failure or null
 * result. The single installed provider is stubbed by overriding {@code firstProvider()} — no CDI boot.
 */
class MemorySelectorTest {

    private static final MemoryQuery QUERY = new MemoryQuery("main", "s1", "how tall is it?");
    private static final MemoryPolicy HYBRID = MemoryPolicy.defaults();
    private static final MemoryPolicy NONE =
            new MemoryPolicy(RetrievalStrategy.NONE, EnumSet.noneOf(MemoryTier.class), 8, 0.0, 8000);

    private static MemorySelector selectorWith(MemoryProvider provider) {
        return new MemorySelector() {
            @Override
            MemoryProvider firstProvider() {
                return provider;
            }
        };
    }

    private static MemoryProvider provider(List<MemoryHit> hits) {
        return new AbstractMemoryProvider() {
            @Override
            public String extensionId() {
                return "stub";
            }

            @Override
            public List<MemoryHit> retrieve(MemoryQuery query, MemoryPolicy policy) {
                return hits;
            }
        };
    }

    private static final MemoryProvider BOOM = new AbstractMemoryProvider() {
        @Override
        public String extensionId() {
            return "boom";
        }

        @Override
        public List<MemoryHit> retrieve(MemoryQuery query, MemoryPolicy policy) {
            throw new RuntimeException("memory backend is down");
        }
    };

    @Test
    void returnsEmptyWhenNoProviderIsInstalled() {
        assertEquals(List.of(), selectorWith(null).retrieve(QUERY, HYBRID));
    }

    @Test
    void returnsEmptyWhenPolicyIsNull() {
        assertEquals(List.of(), selectorWith(provider(List.of())).retrieve(QUERY, null));
    }

    @Test
    void returnsEmptyAndNeverTouchesTheProviderWhenStrategyIsNone() {
        // BOOM would throw if called — NONE must short-circuit before any provider call.
        assertEquals(List.of(), selectorWith(BOOM).retrieve(QUERY, NONE));
    }

    @Test
    void delegatesToTheInstalledProvider() {
        List<MemoryHit> hits = List.of(new MemoryHit(MemoryTier.SEMANTIC, "a relevant fact", 0.9, "r1"));
        assertEquals(hits, selectorWith(provider(hits)).retrieve(QUERY, HYBRID));
    }

    @Test
    void swallowsAProviderFailureAndReturnsEmpty() {
        assertEquals(List.of(), selectorWith(BOOM).retrieve(QUERY, HYBRID),
                "a retrieval failure must never fail the turn — it degrades to no retrieved memory");
    }

    @Test
    void normalizesANullProviderResultToEmpty() {
        assertEquals(List.of(), selectorWith(provider(null)).retrieve(QUERY, HYBRID));
    }
}
