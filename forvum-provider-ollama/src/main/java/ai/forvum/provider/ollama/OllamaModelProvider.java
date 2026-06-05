package ai.forvum.provider.ollama;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;
import ai.forvum.sdk.ForvumExtension;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Local Ollama model provider (no API key). Resolves any {@code ollama:<model>} ModelRef to a
 * LangChain4j ChatModel built programmatically, so a single bean serves every Ollama model.
 *
 * <p>OllamaChatModel construction is lazy — no connection is opened at build time, only when
 * the first chat request is issued — so this bean starts cleanly with no live Ollama server.
 */
@ForvumExtension
@ApplicationScoped
public class OllamaModelProvider extends AbstractModelProvider {

    private static final String DEFAULT_BASE_URL = "http://localhost:11434";

    @Override
    public String extensionId() {
        return "ollama";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        return OllamaChatModel.builder()
                .baseUrl(DEFAULT_BASE_URL)
                .modelName(ref.model())
                .build();
    }
}
