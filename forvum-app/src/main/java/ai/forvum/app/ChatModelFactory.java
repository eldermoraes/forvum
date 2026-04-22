package ai.forvum.app;

import ai.forvum.core.ModelRef;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;

@ApplicationScoped
public class ChatModelFactory {

    private static final String OLLAMA_DEFAULT_BASE_URL = "http://localhost:11434";
    private static final Duration OLLAMA_TIMEOUT = Duration.ofMinutes(2);

    public ChatLanguageModel resolve(ModelRef ref) {
        return switch (ref.provider()) {
            case "ollama" -> OllamaChatModel.builder()
                .baseUrl(OLLAMA_DEFAULT_BASE_URL)
                .modelName(ref.model())
                .timeout(OLLAMA_TIMEOUT)
                .build();
            default -> throw new IllegalStateException(
                "ChatModelFactory MVP only supports provider 'ollama'. "
              + "Got: '" + ref.provider() + "'. "
              + "See docs/design-rounds/demo-mvp-deferrals.md §D1 — full "
              + "ModelProvider SPI deferred to post-MVP.");
        };
    }
}
