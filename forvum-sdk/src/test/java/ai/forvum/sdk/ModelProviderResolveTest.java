package ai.forvum.sdk;

import static org.junit.jupiter.api.Assertions.assertSame;

import ai.forvum.core.ModelRef;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the {@link ModelProvider} resolution method (ULTRAPLAN section 4.3.5.1): a model
 * plugin resolves a {@link ModelRef} to a LangChain4j {@link ChatModel}. The method lands in the SDK
 * as the Tier-B prelude (in the M7 PR) because the engine's {@code LlmSelector} consumes it and M7
 * merges before M9 — which implements it for Ollama.
 */
class ModelProviderResolveTest {

    @Test
    void resolveReturnsTheChatModelForTheGivenRef() {
        ChatModel stub = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return null;
            }
        };
        ModelProvider provider = new AbstractModelProvider() {
            @Override
            public String extensionId() {
                return "fake";
            }

            @Override
            public ChatModel resolve(ModelRef ref) {
                return stub;
            }
        };

        ChatModel resolved = provider.resolve(ModelRef.parse("fake:qwen3:1.7b"));

        assertSame(stub, resolved);
    }
}
