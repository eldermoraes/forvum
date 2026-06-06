package ai.forvum.app;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A deterministic in-process {@link ai.forvum.sdk.ModelProvider} (extension id {@code fake}) on the
 * forvum-app test classpath, so {@code WebScriptedTurnE2E} can drive a real turn end-to-end without a
 * live LLM. Mirrors the engine-side test fake; its {@code resolve} returns a {@link ChatModel} that
 * always replies {@code "pong"}. The provider-resolve guards inject providers by concrete type, so this
 * extra bean does not perturb them.
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
