package ai.forvum.provider.openai;

import ai.forvum.core.ModelRef;
import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Contract tests for {@link OpenAiModelProvider}.
 *
 * <p>Like {@code AnthropicChatModel}, {@code OpenAiChatModel} built from the Quarkiverse extension
 * uses a Quarkus Reactive REST Client ({@code OpenAiRestApi}) that requires an active ArC CDI
 * context at construction time. Therefore these tests boot Quarkus via {@code @QuarkusTest} and
 * inject the provider as a CDI bean.
 *
 * <p>No live OpenAI service is needed: the {@code resolve()} tests only assert that a non-null
 * {@code ChatModel} is returned and that the same instance is cached — no {@code chat()} call is made.
 */
@QuarkusTest
class OpenAiModelProviderTest {

    @Inject
    OpenAiModelProvider provider;

    @Test
    void extensionId_is_openai() {
        assertEquals("openai", provider.extensionId());
    }

    @Test
    void resolve_builds_a_chat_model_for_an_openai_ref() {
        // Building an OpenAiChatModel constructs the underlying HTTP client but opens no connection;
        // no live OpenAI service is required. gpt-4o-mini is the representative live model id.
        ChatModel model = provider.resolve(ModelRef.parse("openai:gpt-4o-mini"));
        assertNotNull(model);
    }

    @Test
    void resolve_caches_one_model_per_model_id() {
        ChatModel first = provider.resolve(ModelRef.parse("openai:gpt-4o-mini"));
        ChatModel again = provider.resolve(ModelRef.parse("openai:gpt-4o-mini"));
        assertSame(first, again, "the same model id must resolve to the same cached ChatModel");
    }
}
