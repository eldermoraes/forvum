package ai.forvum.provider.ollama;

import ai.forvum.core.ModelRef;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class OllamaModelProviderTest {
    private final OllamaModelProvider provider = new OllamaModelProvider();

    {
        // Plain unit test (no CDI), so the @ConfigProperty base URL is not injected — set it directly
        // (same package → package-private field is accessible). An @QuarkusTest would inject it instead.
        provider.baseUrl = "http://localhost:11434";
    }

    @Test
    void extensionId_is_ollama() {
        assertEquals("ollama", provider.extensionId());
    }

    @Test
    void resolve_builds_a_chat_model_for_an_ollama_ref() {
        // ModelRef.parse splits on FIRST colon only: "ollama:qwen3:1.7b" -> provider="ollama", model="qwen3:1.7b".
        // Building an OllamaChatModel is lazy (no connection opened), so this needs no live server.
        ChatModel model = provider.resolve(ModelRef.parse("ollama:qwen3:1.7b"));
        assertNotNull(model);
    }

    @Test
    void resolve_caches_one_model_per_model_id() {
        ChatModel first = provider.resolve(ModelRef.parse("ollama:qwen3:1.7b"));
        ChatModel again = provider.resolve(ModelRef.parse("ollama:qwen3:1.7b"));
        assertSame(first, again, "the same model id must resolve to the same cached ChatModel");
    }

    @Test
    void resolveEmbedding_builds_an_embedding_model_for_an_ollama_ref() {
        // OllamaEmbeddingModel.builder() is lazy (no connection opened), so this needs no live server (#50).
        EmbeddingModel model = provider.resolveEmbedding(ModelRef.parse("ollama:nomic-embed-text"));
        assertNotNull(model);
    }

    @Test
    void resolveEmbedding_caches_one_model_per_model_id() {
        EmbeddingModel first = provider.resolveEmbedding(ModelRef.parse("ollama:nomic-embed-text"));
        EmbeddingModel again = provider.resolveEmbedding(ModelRef.parse("ollama:nomic-embed-text"));
        assertSame(first, again, "the same model id must resolve to the same cached EmbeddingModel");
    }
}
