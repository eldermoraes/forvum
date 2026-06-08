package ai.forvum.provider.memory.qdrant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Request body for Qdrant {@code POST /collections/{collection}/points/search} (vector search). A null
 * {@code filter} or {@code scoreThreshold} is omitted from the JSON ({@code JsonInclude.NON_NULL}). Field
 * names map to Qdrant's snake_case keys via {@link JsonProperty}.
 *
 * @param vector         the dense query vector (the reference embedding of the query text).
 * @param limit          maximum number of points to return (the policy's {@code topK}).
 * @param scoreThreshold optional minimum Qdrant score; null when the policy's minScore is 0.
 * @param withPayload    whether to return the point payload (always true — content lives there).
 * @param filter         optional payload filter restricting the search (tier / agent / session scope).
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QdrantSearchRequest(
        @JsonProperty("vector") List<Float> vector,
        @JsonProperty("limit") int limit,
        @JsonProperty("score_threshold") Double scoreThreshold,
        @JsonProperty("with_payload") boolean withPayload,
        @JsonProperty("filter") QdrantFilter filter) {
}
