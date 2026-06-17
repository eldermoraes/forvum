package ai.forvum.tools.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One Brave Search web result: {@code { "title", "url", "description", ... }}. A record carrying the real
 * Quarkus {@link RegisterForReflection} so Jackson can bind it in the native image (Layer-3 module is
 * Quarkus-bearing; the qdrant-DTO native-clean pattern). Unknown fields are ignored — Brave returns many
 * more than the three Forvum surfaces.
 *
 * @param title       the result title
 * @param url         the result URL
 * @param description the snippet/description
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record BraveWebResult(@JsonProperty("title") String title,
                             @JsonProperty("url") String url,
                             @JsonProperty("description") String description) {
}
