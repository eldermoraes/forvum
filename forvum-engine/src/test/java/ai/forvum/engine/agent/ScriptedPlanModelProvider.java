package ai.forvum.engine.agent;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A deterministic in-process {@code ModelProvider} (extension id {@code scriptedplan}) that drives the
 * #190 {@code update_plan} loop AND captures every {@link ChatRequest#messages()} it is handed (the
 * [M18] green-for-wrong-reason guard at the integration level). Stateless across turns — it decides
 * from the conversation: a turn whose last user message asks to {@code "make a plan"} and that carries
 * no tool result yet emits one {@code update_plan} call; once a tool result is present it answers;
 * any other turn answers directly. Used by {@code AgentPlanTurnIT}.
 */
@ApplicationScoped
public class ScriptedPlanModelProvider extends AbstractModelProvider {

    /** Every conversation the model saw, in call order (clear between scenarios). */
    public static final List<List<ChatMessage>> SEEN = new CopyOnWriteArrayList<>();

    private static final String PLAN_ARGS =
            "{\"plan\":[{\"step\":\"gather the inputs\",\"status\":\"in_progress\"},"
          + "{\"step\":\"write the summary\",\"status\":\"pending\"}]}";

    @Override
    public String extensionId() {
        return "scriptedplan";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                SEEN.add(List.copyOf(request.messages()));
                boolean toolAlreadyRun = request.messages().stream()
                        .anyMatch(ToolExecutionResultMessage.class::isInstance);
                AiMessage reply;
                if (!toolAlreadyRun && lastUserTextContains(request.messages(), "make a plan")) {
                    reply = AiMessage.builder().toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                            .id("plan-1").name("update_plan").arguments(PLAN_ARGS).build())).build();
                } else if (toolAlreadyRun) {
                    reply = AiMessage.from("plan recorded, starting");
                } else {
                    reply = AiMessage.from("continuing the plan");
                }
                return ChatResponse.builder().aiMessage(reply).build();
            }
        };
    }

    private static boolean lastUserTextContains(List<ChatMessage> messages, String text) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage user) {
                return user.singleText().contains(text);
            }
        }
        return false;
    }
}
