package ai.forvum.core;

/**
 * A single retrieved memory item returned by {@code MemoryProvider.retrieve} (ULTRAPLAN section 4.3.6;
 * the Context-Engineering Select pillar; DR-5). The host selects, orders, and (when oversized) compresses
 * hits before they re-enter the context window.
 *
 * @param tier    the memory tier the item came from (non-null).
 * @param content the retrieved text (non-null; may be empty).
 * @param score   the provider's relevance score for this hit, in {@code [0, 1]} (higher is more
 *                relevant); the host filters by {@link MemoryPolicy#minScore()} and orders by score.
 * @param source  free-form provenance (e.g. a row id, a document key); non-null, may be empty.
 */
public record MemoryHit(MemoryTier tier, String content, double score, String source) {
    public MemoryHit {
        if (tier == null) {
            throw new IllegalStateException(
                "MemoryHit tier must be non-null. Every hit is attributed to a MemoryTier.");
        }
        if (content == null) {
            throw new IllegalStateException(
                "MemoryHit content must be non-null (use an empty string for an empty hit).");
        }
        if (Double.isNaN(score) || score < 0.0 || score > 1.0) {
            throw new IllegalStateException(
                "MemoryHit score must be in [0, 1]. Got: " + score + ". A provider must normalize its "
              + "relevance score so the host can apply MemoryPolicy.minScore uniformly.");
        }
        if (source == null) {
            throw new IllegalStateException(
                "MemoryHit source must be non-null (use an empty string when provenance is unknown).");
        }
    }
}
