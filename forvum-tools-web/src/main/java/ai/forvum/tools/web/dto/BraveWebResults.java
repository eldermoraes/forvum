package ai.forvum.tools.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The {@code "web"} block of a Brave Search response: {@code { "web": { "results": [BraveWebResult...] } }}.
 * {@code results} may be {@code null} on a response with no web results, so callers null-guard. A record
 * carrying the real Quarkus {@link RegisterForReflection} (native-clean Jackson binding).
 *
 * @param results the web results, or null when absent.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record BraveWebResults(@JsonProperty("results") List<BraveWebResult> results) {
}
