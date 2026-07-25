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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A deterministic in-process {@link ai.forvum.sdk.ModelProvider} (extension id {@code scripted-web-search})
 * on the forvum-app test classpath (#184): on the first turn it emits a {@code web.search} tool call, and
 * once a tool result has been fed back it answers {@code "done"}. It RECORDS every {@link ChatRequest} it is
 * handed so a test can inspect the tool specifications the engine offered the model (the scopeVisibleBelt
 * result) and the tool-result message that came back. Stateless across turns (it decides from the
 * conversation it is handed), so a single cached instance is correct for every turn; tests
 * {@link #reset() reset} the capture between dispatches.
 */
@ApplicationScoped
public class ScriptedWebSearchModelProvider extends AbstractModelProvider {

    /** Every request the model saw, in order — the tests read toolSpecifications() / messages() off these. */
    private final List<ChatRequest> requests = new CopyOnWriteArrayList<>();

    /**
     * The captured requests, via a METHOD (not the field) — this bean is {@code @ApplicationScoped}, so a
     * test that read the field on the CDI client proxy would see the proxy's always-empty field, not the
     * contextual instance's. A method call is dispatched to the real instance.
     */
    List<ChatRequest> capturedRequests() {
        return requests;
    }

    void reset() {
        requests.clear();
    }

    @Override
    public String extensionId() {
        return "scripted-web-search";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                requests.add(request);
                boolean toolRan = request.messages().stream()
                        .anyMatch(ToolExecutionResultMessage.class::isInstance);
                AiMessage reply = toolRan
                        ? AiMessage.from("done")
                        : AiMessage.builder().toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                                .id("call-1").name("web.search")
                                .arguments("{\"query\":\"forvum\"}").build())).build();
                return ChatResponse.builder().aiMessage(reply).build();
            }
        };
    }
}
