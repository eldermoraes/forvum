package ai.forvum.tools.mediagen.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The OpenAI-compatible envelope for {@code POST /v1/images/generations}:
 * {@code { "created": ..., "data": [ { "b64_json": "..." } ] }}. {@code data} may be {@code null} on an
 * error envelope, so callers must null-guard.
 *
 * @param data the generated images (one entry per requested image), or null on an error envelope.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImageGenerationResponse(@JsonProperty("data") List<GeneratedImage> data) {
}
