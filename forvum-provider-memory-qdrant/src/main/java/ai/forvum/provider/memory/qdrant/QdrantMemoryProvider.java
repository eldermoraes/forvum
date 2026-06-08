package ai.forvum.provider.memory.qdrant;

import ai.forvum.core.MemoryHit;
import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.MemoryQuery;
import ai.forvum.core.MemoryTier;
import ai.forvum.core.RetrievalStrategy;

import ai.forvum.provider.memory.qdrant.dto.QdrantPoint;
import ai.forvum.provider.memory.qdrant.dto.QdrantScrollResponse;
import ai.forvum.provider.memory.qdrant.dto.QdrantSearchResponse;

import ai.forvum.sdk.AbstractMemoryProvider;
import ai.forvum.sdk.ForvumExtension;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * REFERENCE external {@code MemoryProvider} served by a Qdrant vector database over its REST API (P2-5;
 * ULTRAPLAN §7.2 item 5; the Context-Engineering Select pillar). It demonstrates the host
 * {@link ai.forvum.sdk.MemoryProvider} SPI end-to-end for third-party implementors (Redis, Chroma, ...):
 * {@link ForvumExtension} + {@code META-INF/forvum/plugin.json} (provider {@code "type": "memory"}) make
 * it build-time discoverable, and {@code retrieve} blocks on a virtual thread through a blocking REST
 * client — no AI library, no engine dependency, no reactive types.
 *
 * <p><strong>Inert until configured.</strong> With no {@code memory/qdrant.json} (or {@code "enabled":
 * false}, or no {@code url}) the provider returns an empty list and issues no call, so it is safe in the
 * CI native no-config smoke and is never the active provider just by being bundled — an operator opts in.
 *
 * <p><strong>Reference embedding.</strong> Turning the query text into a vector uses
 * {@link ReferenceEmbedding} — a documented, deterministic, dependency-free hashing vector — so the module
 * is hermetic and native-clean. It is structurally correct but NOT semantically meaningful; an operator
 * supplies a real embedding for production. The {@code METADATA} strategy needs no embedding at all (it
 * uses Qdrant scroll + payload filter), so at least one path is embedding-free.
 */
@ForvumExtension
@ApplicationScoped
public class QdrantMemoryProvider extends AbstractMemoryProvider {

    private static final Logger LOG = Logger.getLogger(QdrantMemoryProvider.class);

    private final QdrantConfig config;
    private final QdrantApi api;

    @Inject
    public QdrantMemoryProvider(QdrantConfig config, @RestClient QdrantApi api) {
        this.config = config;
        this.api = api;
    }

    @Override
    public String extensionId() {
        return "memory-qdrant";
    }

    @Override
    public List<MemoryHit> retrieve(MemoryQuery query, MemoryPolicy policy) {
        if (policy.strategy() == RetrievalStrategy.NONE) {
            return List.of();
        }
        QdrantConfig.Spec spec = config.read();
        if (!spec.isActive()) {
            LOG.debug("Qdrant memory provider is not configured (memory/qdrant.json absent or disabled); "
                    + "returning no hits.");
            return List.of();
        }

        String baseUrl = spec.url().orElseThrow();
        List<MemoryHit> collected = new ArrayList<>();
        try {
            for (MemoryTier tier : policy.tiers()) {
                if (QdrantRetrieval.isMetadataStrategy(policy)) {
                    collected.addAll(scrollTier(baseUrl, spec, query, policy, tier));
                } else {
                    collected.addAll(searchTier(baseUrl, spec, query, policy, tier));
                }
            }
        } catch (RuntimeException e) {
            // A backend error must not crash the turn; the host degrades to no retrieval (the same
            // posture as an unconfigured provider). The cause is logged for the operator.
            LOG.warnf(e, "Qdrant retrieval failed for agent=%s session=%s; returning no hits.",
                    query.agentId(), query.sessionId());
            return List.of();
        }
        return QdrantRetrieval.merge(collected, policy);
    }

    private List<MemoryHit> searchTier(String baseUrl, QdrantConfig.Spec spec,
                                       MemoryQuery query, MemoryPolicy policy, MemoryTier tier) {
        QdrantSearchResponse response =
                api.search(baseUrl, spec.apiKey(), spec.collection(),
                        QdrantRetrieval.searchRequest(query, policy, tier));
        // A scored search hit carries its own Qdrant score, so the default is never consulted.
        return mapPoints(response == null ? null : response.result(), tier, Double.NaN);
    }

    private List<MemoryHit> scrollTier(String baseUrl, QdrantConfig.Spec spec,
                                       MemoryQuery query, MemoryPolicy policy, MemoryTier tier) {
        QdrantScrollResponse response =
                api.scroll(baseUrl, spec.apiKey(), spec.collection(),
                        QdrantRetrieval.scrollRequest(query, policy, tier));
        List<QdrantPoint> points =
                response == null || response.result() == null ? null : response.result().points();
        // A scroll point is unscored; an exact filter match scores METADATA_SCORE.
        return mapPoints(points, tier, QdrantRetrieval.METADATA_SCORE);
    }

    private List<MemoryHit> mapPoints(List<QdrantPoint> points, MemoryTier tier, double defaultScore) {
        if (points == null) {
            return List.of();
        }
        List<MemoryHit> hits = new ArrayList<>(points.size());
        for (QdrantPoint point : points) {
            MemoryHit hit = QdrantRetrieval.toHit(point, tier, defaultScore);
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }
}
