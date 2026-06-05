package ai.forvum.provider.google;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;
import ai.forvum.sdk.ForvumExtension;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import io.quarkiverse.langchain4j.jaxrsclient.JaxRsHttpClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Google Gemini model provider via the REST Gemini Developer API.
 *
 * <p>This provider uses the {@code quarkus-langchain4j-ai-gemini} Quarkiverse extension (REST,
 * API-key based) — NOT {@code quarkus-langchain4j-vertex-ai-gemini} (gRPC). The gRPC Vertex AI
 * extension is avoided because it is fragile in GraalVM native images (Risk #5, CLAUDE.md §5);
 * the REST extension is the preferred native-first remedy documented there.
 *
 * <p>Resolves any {@code google:<model>} ModelRef to a LangChain4j ChatModel built
 * programmatically, so a single bean serves every Gemini model.
 *
 * <p>The API key is read from config ({@code quarkus.langchain4j.ai.gemini.api-key}, default empty)
 * — "fixed code, configurable behavior" (CLAUDE.md §1): an operator sets the key in config without
 * recompiling. The default is empty so the bean starts with no key at the native boot smoke
 * (no {@code ~/.forvum/}); the key is required only when {@code chat()} is called.
 *
 * <p>GoogleAiGeminiChatModel construction is lazy — the underlying Quarkus Reactive REST Client is
 * built when the first {@link #resolve} call is made, not at bean startup — so this bean starts
 * cleanly with no live Google service. Built models are cached per model id: each build allocates
 * an underlying HTTP client, so re-resolving the same {@code google:<model>} on every turn would
 * churn clients; the cache reuses one model per id.
 *
 * <p><strong>HTTP client pin (load-bearing):</strong> unlike {@code OpenAiChatModel} and
 * {@code AnthropicChatModel} — whose programmatic {@code builder()} is swapped to the Quarkus REST
 * client by a Quarkiverse builder-factory — {@code GoogleAiGeminiChatModel.builder()} is the raw
 * LangChain4j builder. When built without an explicit {@code httpClientBuilder}, {@code GeminiService}
 * resolves one via {@code dev.langchain4j.http.client.HttpClientBuilderLoader}, a {@code ServiceLoader}
 * that throws {@code IllegalStateException("Conflict: multiple HTTP clients ...")} when more than one
 * {@code HttpClientBuilderFactory} is on the classpath and no {@code langchain4j.http.clientBuilderFactory}
 * system property is set. The full {@code forvum-app} classpath carries two such factories at once
 * ({@code JaxRsHttpClientBuilderFactory} via ollama/gemini and {@code JdkHttpClientBuilderFactory} via
 * anthropic), so every {@code google:<model>} turn would throw. This provider therefore pins an explicit
 * {@link JaxRsHttpClientBuilder} — the Quarkus REST client (native-tested, the same stack the OpenAI and
 * Anthropic builders land on, and what the ai-gemini extension's own recorder uses). {@code GeminiService}
 * fills the builder's connect/read timeouts from {@code timeout} before building, so a bare
 * {@code new JaxRsHttpClientBuilder()} is safe. The conflict is invisible to the provider-module contract
 * test (its classpath has a single factory) — {@code ProviderResolveInAppClasspathTest} in {@code forvum-app}
 * is the regression guard.
 *
 * <p>Timeout note: {@code quarkus.langchain4j.ai.gemini.timeout} is managed by the Quarkiverse
 * extension using expression interpolation referencing {@code quarkus.langchain4j.timeout}, which
 * is absent in Forvum's config and causes a {@code SRCFG00011} boot error if read directly via
 * {@code @ConfigProperty}. A Forvum-namespaced key ({@code forvum.provider.google.timeout}) is
 * used instead — it is independent, safe, and consistent with the OpenAI and Anthropic providers.
 * Defaults to 30 s.
 *
 * <p><strong>API key environment variable:</strong> {@code QUARKUS_LANGCHAIN4J_AI_GEMINI_API_KEY}
 * (the standard Quarkus env-var form of {@code quarkus.langchain4j.ai.gemini.api-key}: dots
 * replaced by underscores, all uppercase).
 */
@ForvumExtension
@ApplicationScoped
public class GoogleModelProvider extends AbstractModelProvider {

    /** Google Gemini API key; package-private for ArC field injection. */
    @ConfigProperty(name = "quarkus.langchain4j.ai.gemini.api-key", defaultValue = "")
    String apiKey;

    /**
     * HTTP request timeout for programmatic model construction; package-private for ArC field
     * injection. Uses a Forvum-namespaced key ({@code forvum.provider.google.timeout}) to avoid a
     * conflict with the Quarkiverse extension's own {@code quarkus.langchain4j.ai.gemini.timeout}
     * binding: that key uses expression interpolation referencing {@code quarkus.langchain4j.timeout},
     * which is absent in Forvum's config, causing a {@code SRCFG00011} boot error if read directly via
     * {@code @ConfigProperty}. The Forvum key is independent and safe. Defaults to 30 s.
     */
    @ConfigProperty(name = "forvum.provider.google.timeout", defaultValue = "30S")
    Duration timeout;

    private final ConcurrentMap<String, ChatModel> modelsByName = new ConcurrentHashMap<>();

    @Override
    public String extensionId() {
        return "google";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        // Pin an explicit JaxRsHttpClientBuilder so GeminiService never takes the ambiguous
        // HttpClientBuilderLoader ServiceLoader path (two factories on the forvum-app classpath ->
        // IllegalStateException); see the class Javadoc. The CHM computeIfAbsent callback is
        // synchronous and short (no I/O at build time), so it does not pin the carrier thread.
        return modelsByName.computeIfAbsent(ref.model(), modelName -> GoogleAiGeminiChatModel.builder()
                .httpClientBuilder(new JaxRsHttpClientBuilder())
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(timeout)
                .build());
    }
}
