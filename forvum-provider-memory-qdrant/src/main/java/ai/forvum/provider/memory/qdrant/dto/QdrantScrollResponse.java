package ai.forvum.provider.memory.qdrant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The Qdrant envelope for {@code points/scroll}: {@code { "result": { "points": [Record...],
 * "next_page_offset": ... }, "status": "ok" }}. Forvum reads only the first page ({@code result.points})
 * — the policy's {@code topK} bounds the page — so {@code next_page_offset} is ignored.
 *
 * @param result the scroll page wrapper (may be null on an error envelope).
 * @param status the operation status (e.g. {@code "ok"}).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record QdrantScrollResponse(@JsonProperty("result") Page result,
                                   @JsonProperty("status") String status) {

    /** The scroll page: a list of points plus the next-page offset (ignored by Forvum). */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Page(@JsonProperty("points") List<QdrantPoint> points) {
    }
}
