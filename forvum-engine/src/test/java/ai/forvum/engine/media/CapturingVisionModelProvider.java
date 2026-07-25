package ai.forvum.engine.media;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A deterministic in-process {@link ai.forvum.sdk.ModelProvider} (extension id {@code capture}) for the
 * {@link EngineMediaAnalysis} tests. Its model records the {@link ModelRef} it was resolved with and the
 * {@link ChatRequest} it was called with into STATIC holders (sidestepping the [#184] CDI-proxy field trap),
 * then replies with a canned analysis string.
 */
@ApplicationScoped
public class CapturingVisionModelProvider extends AbstractModelProvider {

    static final AtomicReference<ModelRef> LAST_REF = new AtomicReference<>();
    static final AtomicReference<ChatRequest> LAST_REQUEST = new AtomicReference<>();

    static void reset() {
        LAST_REF.set(null);
        LAST_REQUEST.set(null);
    }

    @Override
    public String extensionId() {
        return "capture";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        LAST_REF.set(ref);
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                LAST_REQUEST.set(request);
                return ChatResponse.builder().aiMessage(AiMessage.from("analysis-ok")).build();
            }
        };
    }
}
