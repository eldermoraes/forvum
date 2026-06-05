package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.forvum.core.ModelRef;
import ai.forvum.provider.anthropic.AnthropicModelProvider;
import ai.forvum.provider.google.GoogleModelProvider;
import ai.forvum.provider.ollama.OllamaModelProvider;
import ai.forvum.provider.openai.OpenAiModelProvider;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

/**
 * Regression guard for the multi-factory {@code HttpClientBuilder} conflict (M12 code-review). Only
 * the full {@code forvum-app} classpath carries BOTH {@code dev.langchain4j} HTTP-client factories at
 * once — {@code JaxRsHttpClientBuilderFactory} (via the ollama/gemini extensions) and
 * {@code JdkHttpClientBuilderFactory} (a transitive of several langchain4j model libs, e.g. anthropic).
 * When a LangChain4j model is built without an explicit {@code httpClientBuilder} and no
 * {@code langchain4j.http.clientBuilderFactory} system property is set,
 * {@code dev.langchain4j.http.client.HttpClientBuilderLoader} throws
 * {@code IllegalStateException("Conflict: multiple HTTP clients ...")} at {@code build()} time.
 *
 * <p>Two of the four providers build models whose {@code builder()} routes through that loader —
 * <strong>Gemini and Ollama</strong> (their Quarkiverse extensions ship no client-builder-factory
 * service to swap the builder). OpenAI/Anthropic are swapped to the Quarkus REST client by their
 * extensions' factories, so they never reach the loader. The conflict is resolved app-wide by
 * {@link HttpClientFactorySelector}, which names the factory via the
 * {@code langchain4j.http.clientBuilderFactory} system property at startup; without that selector the
 * Gemini and Ollama resolves below throw.
 *
 * <p>This test resolves <strong>every</strong> provider so it doubles as the scalability guard: a new
 * un-swapped provider added later — or a drift in the selected factory name — fails here. It is the ONLY
 * test that reproduces the conflict (the provider-module contract tests run against a single-factory
 * module classpath and pass regardless). It is non-live — {@code resolve} only builds the model (no
 * network, no API key needed) — and intentionally NOT {@code @Tag("live")} so the default build catches a
 * regression. The exception propagates out of {@code resolve} before {@code assertNotNull}, so a broken
 * selector fails the test directly with the conflict stack trace.
 */
@QuarkusTest
class ProviderResolveInAppClasspathTest {

    @Inject
    GoogleModelProvider google;

    @Inject
    OllamaModelProvider ollama;

    @Inject
    OpenAiModelProvider openai;

    @Inject
    AnthropicModelProvider anthropic;

    @Test
    void geminiAndOllamaResolveDespiteMultipleHttpClientFactories() {
        // The two un-swapped providers: without the app-wide HttpClientFactorySelector, build() throws the conflict.
        assertNotNull(google.resolve(ModelRef.parse("google:gemini-2.0-flash")),
                "google:<model> must resolve in the full app classpath without an HTTP-client conflict");
        assertNotNull(ollama.resolve(ModelRef.parse("ollama:llama3.2")),
                "ollama:<model> must resolve in the full app classpath without an HTTP-client conflict");
    }

    @Test
    void openaiAndAnthropicResolveInAppClasspath() {
        // The swapped providers: confirm they also resolve (and pin the Gemini/Ollama-only scope).
        assertNotNull(openai.resolve(ModelRef.parse("openai:gpt-4o-mini")),
                "openai:<model> must resolve in the full app classpath");
        assertNotNull(anthropic.resolve(ModelRef.parse("anthropic:claude-3-5-haiku-20241022")),
                "anthropic:<model> must resolve in the full app classpath");
    }
}
