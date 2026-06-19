package ai.forvum.engine.memoryquery;

/**
 * One nearest-neighbor result of {@code forvum memory search '<text>'} (P3-2, #50): a {@code semantic_memory}
 * row plus its cosine similarity to the query embedding. Ordered most-similar-first by the search. Printed
 * by the CLI; never JSON-serialized, so it carries NO {@code @RegisterForReflection}.
 *
 * @param identityId the owning identity (#53 multi-user)
 * @param agentId    the owning agent
 * @param key        the fact key
 * @param value      the fact value
 * @param score      cosine similarity in {@code [-1, 1]}, higher is more similar
 */
public record SearchHit(String identityId, String agentId, String key, String value, double score) {
}
