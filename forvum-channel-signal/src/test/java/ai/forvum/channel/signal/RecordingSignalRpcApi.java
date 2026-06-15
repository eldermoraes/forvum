package ai.forvum.channel.signal;

import ai.forvum.channel.signal.dto.JsonRpcRequest;
import ai.forvum.channel.signal.dto.JsonRpcResponse;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test double for {@link SignalRpcApi}: records every JSON-RPC request (with the base URL it was
 * addressed to) and replies with a success envelope. Used directly (not as a CDI bean) so
 * {@code EnvelopeProcessor.process} and the channel's consume loop can be driven without a live
 * signal-cli daemon or a mocked HTTP endpoint.
 */
class RecordingSignalRpcApi implements SignalRpcApi {

    final CopyOnWriteArrayList<Sent> sent = new CopyOnWriteArrayList<>();

    record Sent(String baseUrl, JsonRpcRequest request) {
    }

    @Override
    public JsonRpcResponse rpc(String baseUrl, JsonRpcRequest request) {
        sent.add(new Sent(baseUrl, request));
        return new JsonRpcResponse(JsonNodeFactory.instance.objectNode(), null);
    }
}
