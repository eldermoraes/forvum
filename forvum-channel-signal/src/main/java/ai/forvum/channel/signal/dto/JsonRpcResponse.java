package ai.forvum.channel.signal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The JSON-RPC 2.0 response envelope from the signal-cli daemon's {@code /api/v1/rpc}: exactly one of
 * {@code result} (kept as a raw {@link JsonNode} — the channel only checks for success) or
 * {@code error} is present. Jackson-DESERIALIZED on every send's reply — hence the
 * {@code @RegisterForReflection} (native rule) and {@code ignoreUnknown} (the daemon may add fields).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record JsonRpcResponse(JsonNode result, JsonRpcError error) {
}
