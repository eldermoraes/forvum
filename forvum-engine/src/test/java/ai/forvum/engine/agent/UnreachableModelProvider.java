package ai.forvum.engine.agent;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.ConnectException;

/**
 * A model provider (extension id {@code unreachable}) whose {@link ChatModel} fails the way a real
 * HTTP provider does when its server is down — a wrapper {@link RuntimeException} carrying a
 * {@link ConnectException} cause (mirroring langchain4j's JDK HTTP client). Used to assert the turn's
 * {@code ErrorEvent} surfaces the root cause and the provider hint, not just the wrapper message.
 */
@ApplicationScoped
public class UnreachableModelProvider extends AbstractModelProvider {

    @Override
    public String extensionId() {
        return "unreachable";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new RuntimeException(new ConnectException("Connection refused"));
            }
        };
    }
}
