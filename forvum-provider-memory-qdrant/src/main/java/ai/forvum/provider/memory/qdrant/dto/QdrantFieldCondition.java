package ai.forvum.provider.memory.qdrant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One Qdrant payload condition: {@code { "key": <field>, "match": { "value": <value> } }}.
 *
 * @param key   the payload field name (e.g. {@code agent_id}, {@code session_id}, {@code tier}).
 * @param match the exact-match value wrapper.
 */
@RegisterForReflection
public record QdrantFieldCondition(@JsonProperty("key") String key,
                                   @JsonProperty("match") QdrantMatch match) {

    /** Build a string exact-match condition on {@code key}. */
    public static QdrantFieldCondition of(String key, String value) {
        return new QdrantFieldCondition(key, new QdrantMatch(value));
    }

    /** The {@code match} wrapper: {@code { "value": <value> }}. */
    @RegisterForReflection
    public record QdrantMatch(@JsonProperty("value") String value) {
    }
}
