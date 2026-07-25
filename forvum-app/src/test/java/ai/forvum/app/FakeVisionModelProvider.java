package ai.forvum.app;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The vision model (extension id {@code vision-capture}) the {@code image.analyze} sub-generation resolves to
 * in {@code MultimodalTurnTest} (via the {@code tools/multimodal.json} model override). It RECORDS every
 * {@link ChatRequest} it is handed so the test can assert the image content (base64 of the exact workspace
 * file bytes) reached it, and replies with a canned analysis.
 */
@ApplicationScoped
public class FakeVisionModelProvider extends AbstractModelProvider {

    private final List<ChatRequest> requests = new CopyOnWriteArrayList<>();

    /** The captured requests, via a METHOD (not the field) so the call is dispatched to the contextual
     *  instance, not the always-empty CDI client proxy field (the [#184] proxy trap). */
    List<ChatRequest> capturedRequests() {
        return requests;
    }

    @Override
    public String extensionId() {
        return "vision-capture";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                requests.add(request);
                return ChatResponse.builder().aiMessage(AiMessage.from("the image shows a test pattern")).build();
            }
        };
    }
}
