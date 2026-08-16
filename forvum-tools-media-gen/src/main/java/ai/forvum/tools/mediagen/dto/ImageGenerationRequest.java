package ai.forvum.tools.mediagen.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Request body for the OpenAI-compatible {@code POST /v1/images/generations} endpoint (#187). A null
 * {@code model} is omitted from the JSON ({@code JsonInclude.NON_NULL}) so the backend's default model
 * applies. {@code response_format} is always {@code b64_json} — the bytes ride back in the response
 * envelope, never behind a URL this module would have to fetch.
 *
 * @param model          the model to generate with, or null to let the backend default.
 * @param prompt         the natural-language description of the image to generate.
 * @param n              the number of images to generate (always 1 — one asset per tool call).
 * @param responseFormat the response encoding (always {@code b64_json}).
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImageGenerationRequest(
        @JsonProperty("model") String model,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("n") int n,
        @JsonProperty("response_format") String responseFormat) {
}
