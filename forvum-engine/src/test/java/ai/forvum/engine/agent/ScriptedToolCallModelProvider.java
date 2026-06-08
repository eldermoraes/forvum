package ai.forvum.engine.agent;

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
 * A deterministic in-process {@link ai.forvum.sdk.ModelProvider} (extension id {@code scripted}) whose
 * model drives the M18 tool loop once: on the first turn it emits a {@code fs.write} tool call, and once a
 * tool result has been fed back it answers {@code "done"}. Stateless across turns (it decides from the
 * conversation it is handed — whether a {@link ToolExecutionResultMessage} is already present — so a single
 * cached instance behaves correctly for every turn). Used by {@code TurnServiceRbacIT} to drive an RBAC
 * scope denial through the real {@code TurnService.dispatch -> SupervisorGraph -> ToolExecutor} path.
 */
@ApplicationScoped
public class ScriptedToolCallModelProvider extends AbstractModelProvider {

    @Override
    public String extensionId() {
        return "scripted";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                boolean toolAlreadyRun = request.messages().stream()
                        .anyMatch(ToolExecutionResultMessage.class::isInstance);
                AiMessage reply = toolAlreadyRun
                        ? AiMessage.from("done")
                        : AiMessage.builder().toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                                .id("call-1").name("fs.write").arguments("{}").build())).build();
                return ChatResponse.builder().aiMessage(reply).build();
            }
        };
    }
}
