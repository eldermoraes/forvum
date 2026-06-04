package ai.forvum.engine.agent;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A model provider (extension id {@code boom}) whose {@link ChatModel} always throws — used to assert
 * that a failed turn leaves no orphan conversational rows while still ledgering the failed attempt.
 */
@ApplicationScoped
public class BoomModelProvider extends AbstractModelProvider {

    @Override
    public String extensionId() {
        return "boom";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new RuntimeException("boom: simulated provider failure");
            }
        };
    }
}
