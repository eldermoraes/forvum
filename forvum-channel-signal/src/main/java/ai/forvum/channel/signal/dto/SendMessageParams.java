package ai.forvum.channel.signal.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The {@code params} of a JSON-RPC {@code send} ({@link JsonRpcRequest}): {@code { "account": "<the
 * daemon-registered number>", "recipient": ["<number or UUID>"], "message": "<text>" }}. Serialized
 * through Jackson on every outbound send — hence the {@code @RegisterForReflection} (native-frame rule).
 */
@RegisterForReflection
public record SendMessageParams(String account, List<String> recipient, String message) {
}
