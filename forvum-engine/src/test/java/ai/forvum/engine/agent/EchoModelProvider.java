package ai.forvum.engine.agent;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A second deterministic in-process provider (extension id {@code echo}) replying a fixed text DISTINCT
 * from {@link FakeModelProvider}'s {@code "pong"}, so a replay-with-substitution test (#57) can prove the
 * SUBSTITUTED model — not the persona model — drove the rerun (the [M19] override-only-if-distinct
 * discipline).
 */
@ApplicationScoped
public class EchoModelProvider extends AbstractModelProvider {

    public static final String REPLY = "echoed-by-substitute";

    @Override
    public String extensionId() {
        return "echo";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(REPLY)).build();
            }
        };
    }
}
