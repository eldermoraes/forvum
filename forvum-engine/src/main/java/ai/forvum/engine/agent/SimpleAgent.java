package ai.forvum.engine.agent;

import ai.forvum.core.AgentSpec;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;

public class SimpleAgent {

    private static final int DEFAULT_MEMORY_WINDOW = 20;

    private final AgentSpec spec;
    private final ChatLanguageModel chatModel;
    private final ChatMemory memory;

    public SimpleAgent(AgentSpec spec, ChatLanguageModel chatModel) {
        if (spec == null) {
            throw new IllegalStateException(
                "SimpleAgent spec must be non-null. "
              + "Caller must load the AgentSpec via AgentSpecLoader before constructing.");
        }
        if (chatModel == null) {
            throw new IllegalStateException(
                "SimpleAgent chatModel must be non-null. "
              + "Caller must resolve the ChatLanguageModel via ChatModelFactory before constructing.");
        }
        this.spec = spec;
        this.chatModel = chatModel;
        this.memory = MessageWindowChatMemory.withMaxMessages(DEFAULT_MEMORY_WINDOW);
        this.memory.add(SystemMessage.from(spec.systemPrompt()));
    }

    public AgentSpec spec() {
        return spec;
    }

    public String chat(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalStateException(
                "SimpleAgent.chat input must be non-null and non-blank. "
              + "Check the CLI read loop for empty-line handling.");
        }
        memory.add(UserMessage.from(userInput));
        ChatResponse response = chatModel.chat(memory.messages());
        AiMessage aiMessage = response.aiMessage();
        memory.add(aiMessage);
        return aiMessage.text();
    }
}
