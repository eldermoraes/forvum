package ai.forvum.sdk;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void resolveEmbeddingDefaultsToUnsupported() {
        // The P3-2 (#50) embedding prelude: a provider that does not override resolveEmbedding rejects it
        // with an actionable message naming the extension — so existing providers compile unchanged.
        ModelProvider provider = new AbstractModelProvider() {
            @Override
            public String extensionId() {
                return "no-embed";
            }

            @Override
            public ChatModel resolve(ModelRef ref) {
                return null;
            }
        };

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> provider.resolveEmbedding(ModelRef.parse("no-embed:some-model")));
        assertTrue(ex.getMessage().contains("no-embed"),
                "the default rejection must name the extension that lacks an embedding model");
    }
}
