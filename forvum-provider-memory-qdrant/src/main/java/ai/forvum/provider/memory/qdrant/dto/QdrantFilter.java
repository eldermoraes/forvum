package ai.forvum.provider.memory.qdrant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * A Qdrant payload filter: a conjunction of {@link QdrantFieldCondition} ({@code must}). Forvum scopes
 * every retrieval to the agent + session and, when the policy restricts tiers, to one tier per request.
 *
 * @param must the conditions that must ALL match (non-null, may be empty).
 */
@RegisterForReflection
public record QdrantFilter(@JsonProperty("must") List<QdrantFieldCondition> must) {
}
