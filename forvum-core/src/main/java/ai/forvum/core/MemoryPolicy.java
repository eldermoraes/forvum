package ai.forvum.core;

import java.util.EnumSet;
import java.util.Set;

/**
 * The per-agent retrieval policy that drives a {@code MemoryProvider} (ULTRAPLAN section 4.3.6; the
 * Context-Engineering Select pillar; DR-5). It is the {@code agents/<id>.json} retrieval spec inherited
 * at spawn, and the single argument (beside the query) the host passes to
 * {@code MemoryProvider.retrieve}.
 *
 * @param strategy               which retrieval mechanism to apply (never null).
 * @param tiers                  the memory tiers the provider may draw from; defensively copied to an
 *                               immutable set. May be empty ONLY when {@code strategy == NONE}
 *                               (retrieval disabled, DR-5 DP-5).
 * @param topK                   the maximum number of hits to return; must be {@code > 0}.
 * @param minScore               the minimum relevance score a hit must clear, in {@code [0, 1]} (0 keeps
 *                               every hit).
 * @param compressThresholdChars the character length above which a retrieved hit is summarized through
 *                               the small-and-fast proxy model before re-entering the context window
 *                               (the Compress pillar); must be {@code >= 0} (0 disables compression).
 */
public record MemoryPolicy(
    RetrievalStrategy strategy,
    Set<MemoryTier> tiers,
    int topK,
    double minScore,
    int compressThresholdChars
) {
    public MemoryPolicy {
        if (strategy == null) {
            throw new IllegalStateException(
                "MemoryPolicy strategy must be non-null. The 'strategy' field in agents/<id>.json "
              + "must name one of VECTOR, GRAPH, METADATA, HYBRID, NONE.");
        }
        if (tiers == null) {
            throw new IllegalStateException(
                "MemoryPolicy tiers must be non-null (use an empty set only with strategy NONE). "
              + "Check the 'tiers' field in agents/<id>.json.");
        }
        tiers = Set.copyOf(tiers);
        if (tiers.isEmpty() && strategy != RetrievalStrategy.NONE) {
            throw new IllegalStateException(
                "MemoryPolicy tiers must be non-empty unless strategy is NONE. Got an empty tier set "
              + "with strategy " + strategy + ". Check agents/<id>.json — either list at least one of "
              + "MESSAGES, EPISODIC, SEMANTIC, or set strategy to NONE to disable retrieval.");
        }
        if (topK <= 0) {
            throw new IllegalStateException(
                "MemoryPolicy topK must be positive. Got: " + topK + ". A non-positive retrieval cap "
              + "is nonsensical — check the 'topK' field in agents/<id>.json.");
        }
        if (Double.isNaN(minScore) || minScore < 0.0 || minScore > 1.0) {
            throw new IllegalStateException(
                "MemoryPolicy minScore must be in [0, 1]. Got: " + minScore + ". Check the 'minScore' "
              + "field in agents/<id>.json.");
        }
        if (compressThresholdChars < 0) {
            throw new IllegalStateException(
                "MemoryPolicy compressThresholdChars must be non-negative (0 disables compression). "
              + "Got: " + compressThresholdChars + ". Check agents/<id>.json.");
        }
    }

    /**
     * The default retrieval policy: {@code HYBRID} strategy over all three tiers, {@code topK = 8},
     * {@code minScore = 0.0} (keep every hit), {@code compressThresholdChars = 8000}. Used for an agent
     * whose {@code agents/<id>.json} omits a retrieval block (DR-5).
     */
    public static MemoryPolicy defaults() {
        return new MemoryPolicy(
            RetrievalStrategy.HYBRID,
            EnumSet.allOf(MemoryTier.class),
            8,
            0.0,
            8000);
    }
}
