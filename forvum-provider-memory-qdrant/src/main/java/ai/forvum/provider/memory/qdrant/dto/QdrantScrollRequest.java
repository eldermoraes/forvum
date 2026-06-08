package ai.forvum.provider.memory.qdrant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Request body for Qdrant {@code POST /collections/{collection}/points/scroll} (the embedding-free
 * METADATA path: page through points by payload filter, no query vector). A null {@code filter} is
 * omitted ({@code JsonInclude.NON_NULL}).
 *
 * @param limit       maximum number of points to return (the policy's {@code topK}).
 * @param withPayload whether to return the point payload (always true — content lives there).
 * @param filter      optional payload filter restricting the scroll (tier / agent / session scope).
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QdrantScrollRequest(
        @JsonProperty("limit") int limit,
        @JsonProperty("with_payload") boolean withPayload,
        @JsonProperty("filter") QdrantFilter filter) {
}
