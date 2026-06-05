package ai.forvum.provider.ollama;

import ai.forvum.core.ModelRef;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OllamaModelProviderTest {
    private final OllamaModelProvider provider = new OllamaModelProvider();

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
}
