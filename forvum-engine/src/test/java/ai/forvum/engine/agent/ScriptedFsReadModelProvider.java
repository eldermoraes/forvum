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
 * A deterministic in-process {@link ai.forvum.sdk.ModelProvider} (extension id {@code scripted-read}) that
 * drives the M18 tool loop once by emitting an {@code fs.read} call, then answers {@code "done"} once a
 * tool result has been fed back. The {@code fs.read} sibling of {@link ScriptedToolCallModelProvider},
 * used by {@code CronSchedulerRoleCapIT} to prove the AGENT role cap further restricts a cron turn beyond
 * the read-only {@code cron} role — an {@code fs.read} the cron role alone would permit is denied when the
 * agent's cap excludes FS_READ. Stateless across turns (it decides from the conversation it is handed).
 */
@ApplicationScoped
public class ScriptedFsReadModelProvider extends AbstractModelProvider {

    @Override
    public String extensionId() {
        return "scripted-read";
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
                                .id("call-1").name("fs.read").arguments("{}").build())).build();
                return ChatResponse.builder().aiMessage(reply).build();
            }
        };
    }
}
