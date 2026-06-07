package ai.forvum.provider.ollama;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;
import ai.forvum.sdk.ForvumExtension;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Local Ollama model provider (no API key). Resolves any {@code ollama:<model>} ModelRef to a
 * LangChain4j ChatModel built programmatically, so a single bean serves every Ollama model.
 *
 * <p>The base URL is read from config ({@code quarkus.langchain4j.ollama.base-url}, default
 * {@code http://localhost:11434}) — "fixed code, configurable behavior" (CLAUDE.md §1): an operator
 * points Forvum at a remote or non-default Ollama host by editing config, without recompiling.
 *
 * <p>OllamaChatModel construction is lazy — no connection is opened at build time, only when the
 * first chat request is issued — so this bean starts cleanly with no live Ollama server. Built models
 * are cached per model id: each build allocates an underlying HTTP client, so re-resolving the same
 * {@code ollama:<model>} on every turn (e.g. a per-minute cron) would churn clients; the cache reuses
 * one model per id.
 *
 * <p><strong>HTTP client (native-critical):</strong> like Gemini (and unlike OpenAI/Anthropic, which are
 * swapped to the Quarkus REST client by their Quarkiverse builder-factories), {@code OllamaChatModel.builder()}
 * is the raw LangChain4j builder. Left to itself it resolves its HTTP client via
 * {@code dev.langchain4j.http.client.HttpClientBuilderLoader}, whose {@code ServiceLoader} lookup returns
 * EMPTY in a GraalVM native image (the langchain4j {@code HttpClientBuilderFactory} providers are not
 * registered for native) — so a native turn fails at {@code build()} with
 * {@code "No HTTP client has been found in the classpath"} (Risk #5; the JVM-only
 * {@code HttpClientFactorySelector} system property cannot populate an empty native ServiceLoader). We
 * therefore pin an explicit {@link JdkHttpClientBuilder} — a pure-LangChain4j JDK {@code java.net.http}
 * client, directly instantiated (so the loader is never consulted) and native-safe — which makes the
 * programmatic Ollama turn work on the native binary. {@code ProviderResolveInAppClasspathTest} guards the
 * assembled classpath; this module's own contract test exercises the same build path.
 */
@ForvumExtension
@ApplicationScoped
public class OllamaModelProvider extends AbstractModelProvider {

    /** Ollama server base URL; package-private for ArC field injection. */
    @ConfigProperty(name = "quarkus.langchain4j.ollama.base-url", defaultValue = "http://localhost:11434")
    String baseUrl;

    private final ConcurrentMap<String, ChatModel> modelsByName = new ConcurrentHashMap<>();

    @Override
    public String extensionId() {
        return "ollama";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        // build() is lazy (allocates no connection), so this never blocks the CHM bin on I/O.
        return modelsByName.computeIfAbsent(ref.model(), modelName -> OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .httpClientBuilder(new JdkHttpClientBuilder()) // explicit: native ServiceLoader is empty (see class javadoc)
                .build());
    }
}
