package ai.forvum.app;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * A deterministic in-process orchestrator model (extension id {@code scripted-mm}) for {@code MultimodalTurnIT}:
 * on the first turn it emits an {@code image.analyze} tool call over a workspace image; once the tool result is
 * fed back it answers {@code "done"}. Stateless across turns (it decides from the conversation it is handed).
 */
@ApplicationScoped
public class ScriptedMultimodalModelProvider extends AbstractModelProvider {

    @Override
    public String extensionId() {
        return "scripted-mm";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                boolean toolRan = request.messages().stream()
                        .anyMatch(ToolExecutionResultMessage.class::isInstance);
                AiMessage reply = toolRan
                        ? AiMessage.from("done")
                        : AiMessage.builder().toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                                .id("call-1").name("image.analyze")
                                .arguments("{\"paths\":[\"a.png\"],\"prompt\":\"describe the image\"}").build()))
                                .build();
                return ChatResponse.builder().aiMessage(reply).build();
            }
        };
    }
}
