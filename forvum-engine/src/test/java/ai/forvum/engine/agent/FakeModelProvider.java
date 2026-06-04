package ai.forvum.engine.agent;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A deterministic in-process {@link ai.forvum.sdk.ModelProvider} (extension id {@code fake}) used to
 * drive the M7 turn path without a real LLM — the engine-side analogue of M9's gated Ollama e2e. Its
 * {@code resolve} returns a {@link ChatModel} that always replies {@code "pong"}.
 */
@ApplicationScoped
public class FakeModelProvider extends AbstractModelProvider {

    @Override
    public String extensionId() {
        return "fake";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from("pong")).build();
            }
        };
    }
}
