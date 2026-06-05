package ai.forvum.provider.google;

import ai.forvum.core.ModelRef;
import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Contract tests for {@link GoogleModelProvider}.
 *
 * <p>Like {@code AnthropicChatModel} and {@code OpenAiChatModel}, {@code GoogleAiGeminiChatModel}
 * built from the Quarkiverse {@code quarkus-langchain4j-ai-gemini} extension uses a Quarkus Reactive
 * REST Client that requires an active ArC CDI context at construction time. Therefore these tests boot
 * Quarkus via {@code @QuarkusTest} and inject the provider as a CDI bean.
 *
 * <p>No live Google Gemini service is needed: the {@code resolve()} tests only assert that a
 * non-null {@code ChatModel} is returned and that the same instance is cached — no {@code chat()}
 * call is made.
 */
@QuarkusTest
class GoogleModelProviderTest {

    @Inject
    GoogleModelProvider provider;

    @Test
    void extensionId_is_google() {
        assertEquals("google", provider.extensionId());
    }

    @Test
    void resolve_builds_a_chat_model_for_a_google_ref() {
        // Building a GoogleAiGeminiChatModel constructs the underlying HTTP client but opens no
        // connection; no live Google service is required. gemini-2.0-flash is the representative model.
        ChatModel model = provider.resolve(ModelRef.parse("google:gemini-2.0-flash"));
        assertNotNull(model);
    }

    @Test
    void resolve_caches_one_model_per_model_id() {
        ChatModel first = provider.resolve(ModelRef.parse("google:gemini-2.0-flash"));
        ChatModel again = provider.resolve(ModelRef.parse("google:gemini-2.0-flash"));
        assertSame(first, again, "the same model id must resolve to the same cached ChatModel");
    }
}
