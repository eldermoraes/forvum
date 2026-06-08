package ai.forvum.provider.memory.qdrant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * One Qdrant point as returned by {@code points/search} (a ScoredPoint, with {@code score}) or
 * {@code points/scroll} (a Record, no {@code score} — null). Forvum reads the {@code payload}, where it
 * expects free-form keys {@code content}, {@code tier}, and {@code source} that the operator's ingestion
 * populated. Unknown fields ({@code version}, {@code vector}, {@code shard_key}, ...) are ignored.
 *
 * @param id      the point id (rendered to string for provenance).
 * @param score   the similarity score for a search hit; null on a scroll result.
 * @param payload the point payload; may be null when {@code with_payload} was not honored.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record QdrantPoint(
        @JsonProperty("id") Object id,
        @JsonProperty("score") Double score,
        @JsonProperty("payload") Map<String, Object> payload) {
}
