package ai.forvum.provider.memory.qdrant;

import ai.forvum.core.MemoryHit;
import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.MemoryQuery;
import ai.forvum.core.MemoryTier;
import ai.forvum.core.RetrievalStrategy;

import ai.forvum.provider.memory.qdrant.dto.QdrantFieldCondition;
import ai.forvum.provider.memory.qdrant.dto.QdrantFilter;
import ai.forvum.provider.memory.qdrant.dto.QdrantPoint;
import ai.forvum.provider.memory.qdrant.dto.QdrantScrollRequest;
import ai.forvum.provider.memory.qdrant.dto.QdrantSearchRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The PURE request-building and response-mapping logic of the Qdrant reference provider — no Quarkus, no
 * IO. It maps {@code (MemoryQuery, MemoryPolicy)} → Qdrant request bodies and Qdrant points → {@link
 * MemoryHit}, honoring the policy's {@code strategy}, {@code tiers}, {@code topK}, and {@code minScore}.
 * Keeping these functions pure makes them unit-testable without an HTTP backend (the orchestration that
 * issues the calls lives in {@link QdrantMemoryProvider}).
 *
 * <p>Forvum's ingestion convention (documented for operators): each Qdrant point's payload carries string
 * keys {@code agent_id}, {@code session_id}, {@code tier} (one of {@code messages|episodic|semantic}),
 * {@code content}, and an optional {@code source}. Every retrieval is scoped to the query's agent and
 * session, and (when a tier is selected) to that one tier.
 */
public final class QdrantRetrieval {

    static final String KEY_AGENT_ID = "agent_id";
    static final String KEY_SESSION_ID = "session_id";
    static final String KEY_TIER = "tier";
    static final String KEY_CONTENT = "content";
    static final String KEY_SOURCE = "source";

    /** The relevance score assigned to an embedding-free METADATA (scroll) hit (an exact filter match). */
    static final double METADATA_SCORE = 1.0;

    private QdrantRetrieval() {
    }

    /** The Qdrant payload {@code tier} value for a {@link MemoryTier} (lower-cased enum name). */
    static String tierValue(MemoryTier tier) {
        return tier.name().toLowerCase(Locale.ROOT);
    }

    /** Whether the policy's strategy is the embedding-free metadata path (Qdrant scroll, no vector). */
    static boolean isMetadataStrategy(MemoryPolicy policy) {
        return policy.strategy() == RetrievalStrategy.METADATA;
    }

    /**
     * Build the vector-search body for one tier. {@code score_threshold} is set to the policy's minScore
     * only when it is positive (0 means "no threshold"); payload is always requested.
     */
    static QdrantSearchRequest searchRequest(MemoryQuery query, MemoryPolicy policy, MemoryTier tier) {
        Double scoreThreshold = policy.minScore() > 0.0 ? policy.minScore() : null;
        return new QdrantSearchRequest(
                ReferenceEmbedding.embed(query.text()),
                policy.topK(),
                scoreThreshold,
                true,
                scopeFilter(query, tier));
    }

    /** Build the scroll body for one tier (the embedding-free metadata path; no vector). */
    static QdrantScrollRequest scrollRequest(MemoryQuery query, MemoryPolicy policy, MemoryTier tier) {
        return new QdrantScrollRequest(policy.topK(), true, scopeFilter(query, tier));
    }

    /** The agent + session + tier conjunction filter scoping a request. */
    static QdrantFilter scopeFilter(MemoryQuery query, MemoryTier tier) {
        return new QdrantFilter(List.of(
                QdrantFieldCondition.of(KEY_AGENT_ID, query.agentId()),
                QdrantFieldCondition.of(KEY_SESSION_ID, query.sessionId()),
                QdrantFieldCondition.of(KEY_TIER, tierValue(tier))));
    }

    /**
     * Map a Qdrant point to a {@link MemoryHit} for {@code tier}. The score is the point's Qdrant score
     * clamped to {@code [0, 1]} (Qdrant cosine scores already fall in {@code [-1, 1]}; a metadata/scroll
     * point has no score, so {@code defaultScore} is used). A point with no {@code content} payload key
     * yields an empty-content hit. Returns {@code null} for a point with no payload at all (skipped).
     */
    static MemoryHit toHit(QdrantPoint point, MemoryTier tier, double defaultScore) {
        Map<String, Object> payload = point.payload();
        if (payload == null) {
            return null;
        }
        String content = stringValue(payload.get(KEY_CONTENT));
        String source = payload.containsKey(KEY_SOURCE)
                ? stringValue(payload.get(KEY_SOURCE))
                : String.valueOf(point.id());
        double raw = point.score() != null ? point.score() : defaultScore;
        double score = clampScore(raw);
        return new MemoryHit(tier, content, score, source);
    }

    /**
     * Merge per-tier hit lists into one ordered result: drop hits below {@code minScore}, sort
     * most-relevant-first, and cap at {@code topK}. (Per-tier requests already cap at topK; the merge
     * re-caps the union.)
     */
    static List<MemoryHit> merge(List<MemoryHit> hits, MemoryPolicy policy) {
        List<MemoryHit> filtered = new ArrayList<>();
        for (MemoryHit hit : hits) {
            if (hit != null && hit.score() >= policy.minScore()) {
                filtered.add(hit);
            }
        }
        filtered.sort(Comparator.comparingDouble(MemoryHit::score).reversed());
        if (filtered.size() > policy.topK()) {
            return List.copyOf(filtered.subList(0, policy.topK()));
        }
        return List.copyOf(filtered);
    }

    private static double clampScore(double raw) {
        if (Double.isNaN(raw)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, raw));
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
