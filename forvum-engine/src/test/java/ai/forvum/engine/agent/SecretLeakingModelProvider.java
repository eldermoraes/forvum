package ai.forvum.engine.agent;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A deterministic in-process model (extension id {@code leak}) whose reply embeds a secret, so the
 * pre-channel-emit {@code OutputGuard} redaction path can be exercised end-to-end (P2-OUTPUTGUARD). It is
 * never selected unless an agent pins {@code leak:<model>}.
 */
@ApplicationScoped
public class SecretLeakingModelProvider extends AbstractModelProvider {

    static final String LEAKED_SECRET = "sk-ant-api03-ABCdef123456ghi789";
    static final String REPLY = "your key is " + LEAKED_SECRET + " keep it safe";

    @Override
    public String extensionId() {
        return "leak";
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
