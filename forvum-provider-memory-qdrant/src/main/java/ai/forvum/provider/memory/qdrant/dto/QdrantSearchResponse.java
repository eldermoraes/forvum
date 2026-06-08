package ai.forvum.provider.memory.qdrant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The Qdrant envelope for {@code points/search}: {@code { "result": [ScoredPoint...], "status": "ok",
 * "time": ... }}. {@code result} may be {@code null} on an error envelope, so callers must null-guard.
 *
 * @param result the scored points (most-similar-first), or null on an error envelope.
 * @param status the operation status (e.g. {@code "ok"}).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record QdrantSearchResponse(@JsonProperty("result") List<QdrantPoint> result,
                                   @JsonProperty("status") String status) {
}
