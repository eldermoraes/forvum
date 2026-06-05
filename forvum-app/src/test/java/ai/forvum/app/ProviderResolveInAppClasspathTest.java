package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.forvum.core.ModelRef;
import ai.forvum.provider.anthropic.AnthropicModelProvider;
import ai.forvum.provider.google.GoogleModelProvider;
import ai.forvum.provider.openai.OpenAiModelProvider;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

/**
 * Regression guard for the multi-factory {@code HttpClientBuilder} conflict (M12 code-review). Only
 * the full {@code forvum-app} classpath carries BOTH {@code dev.langchain4j} HTTP-client factories at
 * once — {@code JaxRsHttpClientBuilderFactory} (via the ollama/gemini extensions) and
 * {@code JdkHttpClientBuilderFactory} (pulled by {@code langchain4j-anthropic}). When a LangChain4j
 * model is built without an explicit {@code httpClientBuilder} and no
 * {@code langchain4j.http.clientBuilderFactory} system property is set,
 * {@code dev.langchain4j.http.client.HttpClientBuilderLoader} throws
 * {@code IllegalStateException("Conflict: multiple HTTP clients ...")}.
 *
 * <p>{@code GoogleAiGeminiChatModel} is the only one of the four whose programmatic builder routes
 * through that loader — the OpenAI/Anthropic builders are swapped to the Quarkus REST client by their
 * Quarkiverse builder-factories, so they never reach it. {@link GoogleModelProvider#resolve} therefore
 * pins an explicit {@code JaxRsHttpClientBuilder}; without that pin the google resolve below throws.
 *
 * <p>This is the ONLY test that reproduces the conflict: the provider-module contract tests
 * ({@code GoogleModelProviderTest} etc.) run against a single-factory module classpath and pass
 * regardless. It is non-live — {@code resolve} only builds the model (no network, no API key needed) —
 * and intentionally NOT {@code @Tag("live")} so the default build catches a regression.
 */
@QuarkusTest
class ProviderResolveInAppClasspathTest {

    @Inject
    GoogleModelProvider google;

    @Inject
    OpenAiModelProvider openai;

    @Inject
    AnthropicModelProvider anthropic;

    @Test
    void googleResolvesDespiteMultipleHttpClientFactories() {
        ChatModel model = google.resolve(ModelRef.parse("google:gemini-2.0-flash"));
        assertNotNull(model, "google:<model> must resolve in the full app classpath without an HTTP-client conflict");
    }

    @Test
    void openaiAndAnthropicResolveInAppClasspath() {
        assertNotNull(openai.resolve(ModelRef.parse("openai:gpt-4o-mini")),
                "openai:<model> must resolve in the full app classpath");
        assertNotNull(anthropic.resolve(ModelRef.parse("anthropic:claude-3-5-haiku-20241022")),
                "anthropic:<model> must resolve in the full app classpath");
    }
}
