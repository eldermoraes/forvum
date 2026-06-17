package ai.forvum.tools.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The Brave Search API web-search envelope: {@code { "web": { "results": [...] }, ... }}. {@code web} may
 * be {@code null} (e.g. a query with no web results), so callers null-guard. A record carrying the real
 * Quarkus {@link RegisterForReflection} so Jackson binds it in the native image (the qdrant-DTO native-clean
 * pattern); unknown top-level blocks ({@code query}, {@code mixed}, {@code news}, ...) are ignored.
 *
 * @param web the web-results block, or null when absent.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record BraveSearchResponse(@JsonProperty("web") BraveWebResults web) {
}
