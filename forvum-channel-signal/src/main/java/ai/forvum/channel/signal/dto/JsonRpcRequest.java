package ai.forvum.channel.signal.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The JSON-RPC 2.0 request envelope the channel POSTs to the signal-cli daemon's {@code /api/v1/rpc}:
 * {@code { "jsonrpc": "2.0", "id": <n>, "method": "send", "params": { ... } }}. Carries
 * {@code @RegisterForReflection} because EVERY outbound send is serialized from this record by the REST
 * client's Jackson writer — without the hint the native binary cannot reflect its accessors and emits an
 * empty/malformed frame, and the no-config native smoke can NOT catch it (no daemon → nothing serialized);
 * the encode test ({@code SignalSendFrameTest}) pins the wire shape (the Discord NATIVE-FRAME lesson).
 */
@RegisterForReflection
public record JsonRpcRequest(String jsonrpc, long id, String method, SendMessageParams params) {

    /**
     * Build a {@code send} request: deliver {@code message} from {@code account} (the daemon-registered
     * number) to the single {@code recipient} (the inbound sender's number or UUID).
     */
    public static JsonRpcRequest send(long id, String account, String recipient, String message) {
        return new JsonRpcRequest("2.0", id, "send",
                new SendMessageParams(account, List.of(recipient), message));
    }
}
