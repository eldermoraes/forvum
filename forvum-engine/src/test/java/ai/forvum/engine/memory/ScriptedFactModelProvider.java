package ai.forvum.engine.memory;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Locale;

/**
 * A deterministic {@code scripted-fact} {@link ai.forvum.sdk.ModelProvider} standing in for the small
 * extraction model in the {@link MemoryWriter} ITs — no live LLM. It branches on a keyword in the turn
 * text so a single fake drives every case: a normal fact, a secret-bearing fact (to exercise the
 * pre-memory-write filter), and an empty extraction.
 */
@ApplicationScoped
public class ScriptedFactModelProvider extends AbstractModelProvider {

    @Override
    public String extensionId() {
        return "scripted-fact";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                String input = lastUserText(request).toLowerCase(Locale.ROOT);
                String json;
                if (input.contains("secretcase")) {
                    json = "[{\"key\":\"api.key\",\"value\":\"my key is sk-live-ABCDEFGHIJKLMNOP1234 keep it\"}]";
                } else if (input.contains("nothingcase")) {
                    json = "[]";
                } else {
                    json = "[{\"key\":\"user.city\",\"value\":\"Berlin\"}]";
                }
                return ChatResponse.builder().aiMessage(AiMessage.from(json)).build();
            }
        };
    }

    private static String lastUserText(ChatRequest request) {
        String text = "";
        for (ChatMessage message : request.messages()) {
            if (message instanceof UserMessage userMessage) {
                text = userMessage.singleText();
            }
        }
        return text;
    }
}
