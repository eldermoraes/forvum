package ai.forvum.engine.agent;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A model provider (extension id {@code leaky}) whose {@link ChatModel} throws an exception whose message
 * and nested cause carry a secret, private paths, a tool argument, and a prompt fragment — the #172 leak
 * fixture. {@code TurnServiceSafeErrorIT} asserts none of them reach the channel-visible error.
 */
@ApplicationScoped
public class LeakyModelProvider extends AbstractModelProvider {

    @Override
    public String extensionId() {
        return "leaky";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new RuntimeException(
                        "provider POST failed with Authorization key sk-LEAKED1234567890abcdef "
                        + "writing /home/victim/.ssh/id_rsa",
                        new IllegalStateException(
                                "tool arg {\"path\":\"/etc/shadow\"} prompt fragment SECRET-PROMPT-XYZ"));
            }
        };
    }
}
