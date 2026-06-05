package ai.forvum.provider.anthropic;

import ai.forvum.core.ModelRef;
import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Contract tests for {@link AnthropicModelProvider}.
 *
 * <p>Unlike OllamaChatModel (plain HTTP, no Quarkus context required), {@code AnthropicChatModel}
 * built from the Quarkiverse extension uses {@code QuarkusAnthropicClient} — a Quarkus Reactive REST
 * Client that requires an active ArC CDI context at construction time. Therefore these tests boot
 * Quarkus via {@code @QuarkusTest} and inject the provider as a CDI bean.
 *
 * <p>No live Anthropic service is needed: the {@code resolve()} tests only assert that a non-null
 * {@code ChatModel} is returned and that the same instance is cached — no {@code chat()} call is made.
 */
@QuarkusTest
class AnthropicModelProviderTest {

    @Inject
    AnthropicModelProvider provider;

    @Test
    void extensionId_is_anthropic() {
        assertEquals("anthropic", provider.extensionId());
    }

    @Test
    void resolve_builds_a_chat_model_for_an_anthropic_ref() {
        // Building an AnthropicChatModel constructs the underlying HTTP client but opens no connection;
        // no live Anthropic service is required. claude-opus-4-6 is the placeholder live model id.
        ChatModel model = provider.resolve(ModelRef.parse("anthropic:claude-opus-4-6"));
        assertNotNull(model);
    }

    @Test
    void resolve_caches_one_model_per_model_id() {
        ChatModel first = provider.resolve(ModelRef.parse("anthropic:claude-opus-4-6"));
        ChatModel again = provider.resolve(ModelRef.parse("anthropic:claude-opus-4-6"));
        assertSame(first, again, "the same model id must resolve to the same cached ChatModel");
    }
}
