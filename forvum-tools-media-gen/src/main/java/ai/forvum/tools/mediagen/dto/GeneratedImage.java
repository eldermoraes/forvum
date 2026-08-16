package ai.forvum.tools.mediagen.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One generated image in an OpenAI-compatible {@code /v1/images/generations} response. The tool always
 * requests {@code response_format=b64_json}, so {@code b64Json} carries the base64-encoded PNG bytes;
 * {@code url} is mapped only so a backend that ignores the requested format can be diagnosed with an
 * actionable error instead of a null-decode failure.
 *
 * @param b64Json the base64-encoded image bytes, or null when the backend returned a URL instead.
 * @param url     a hosted-image URL some backends return when they ignore {@code response_format}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeneratedImage(@JsonProperty("b64_json") String b64Json,
                             @JsonProperty("url") String url) {
}
