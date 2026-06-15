package ai.forvum.channel.signal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A JSON-RPC 2.0 error object ({@code { "code": <n>, "message": "..." }}) on a failed
 * {@link JsonRpcResponse}. Jackson-deserialized — hence the {@code @RegisterForReflection}. The
 * channel logs code + message at WARN (the daemon's error text, never the user's message content).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record JsonRpcError(Integer code, String message) {
}
