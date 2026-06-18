package ai.forvum.provider.google;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;
import ai.forvum.sdk.FileApiKeyStore;
import ai.forvum.sdk.ForvumExtension;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.Optional;
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
 * <p>The API key is read from config ({@code quarkus.langchain4j.ai.gemini.api-key}) — "fixed code,
 * configurable behavior" (CLAUDE.md §1): an operator sets the key in config without recompiling. The
 * {@code defaultValue=""} on the field is only the last-resort fallback if the property is entirely
 * absent; in {@code forvum-app} the property is always present (a {@code unset} placeholder the
 * ai-gemini extension needs to boot — see {@code application.properties}), so the bean starts cleanly
 * with no live key and the key is required only when {@code chat()} is called. When the config key is
 * blank (or the {@code unset} placeholder) the provider falls back to the file the {@code forvum
 * provider add} wizard stored under {@code state/credentials/google} (P2-10 #35, see
 * {@link #effectiveApiKey()}).
 *
 * <p>GoogleAiGeminiChatModel construction is lazy — the underlying Quarkus Reactive REST Client is
 * built when the first {@link #resolve} call is made, not at bean startup — so this bean starts
 * cleanly with no live Google service. Built models are cached per model id: each build allocates
 * an underlying HTTP client, so re-resolving the same {@code google:<model>} on every turn would
 * churn clients; the cache reuses one model per id.
 *
 * <p><strong>HTTP client (native-critical):</strong> unlike {@code OpenAiChatModel} and
 * {@code AnthropicChatModel} — whose programmatic {@code builder()} is swapped to the Quarkus REST client by
 * a Quarkiverse builder-factory — {@code GoogleAiGeminiChatModel.builder()} is the raw LangChain4j builder.
 * Left to itself, {@code GeminiService} resolves its HTTP client via
 * {@code dev.langchain4j.http.client.HttpClientBuilderLoader}, whose {@code ServiceLoader} lookup returns
 * EMPTY in a GraalVM native image (the langchain4j {@code HttpClientBuilderFactory} providers are not
 * registered for native) — so a native turn fails at {@code build()} with
 * {@code "No HTTP client has been found in the classpath"} (Risk #5; the JVM-only
 * {@code HttpClientFactorySelector} system property cannot populate an empty native ServiceLoader). We
 * therefore pin an explicit {@link JdkHttpClientBuilder} — a pure-LangChain4j JDK {@code java.net.http}
 * client, directly instantiated (so the loader is never consulted) and native-safe — mirroring the Ollama
 * provider. {@code ProviderResolveInAppClasspathTest} in {@code forvum-app} is the regression guard.
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

    /**
     * The {@code unset} placeholder the {@code ai-gemini} extension needs to boot with no real key
     * ({@code application.properties}); treated as "no key" so the file-backed fallback applies.
     */
    private static final String GEMINI_PLACEHOLDER = "unset";

    /** Google Gemini API key; package-private for ArC field injection. */
    @ConfigProperty(name = "quarkus.langchain4j.ai.gemini.api-key", defaultValue = "")
    String apiKey;

    /**
     * {@code $FORVUM_HOME} for the file-backed key fallback (P2-10 #35): when {@link #apiKey} is blank
     * (or the {@code unset} placeholder) the provider reads {@code state/credentials/google} — the
     * {@code 0600} file the {@code forvum provider add} wizard writes. Package-private for ArC field
     * injection; an absent value resolves to {@code ~/.forvum}.
     */
    @ConfigProperty(name = "forvum.home")
    Optional<String> forvumHome;

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
        // GeminiService resolves its HTTP client via HttpClientBuilderLoader; the ambiguous-factory
        // conflict on the forvum-app classpath is disambiguated app-wide by HttpClientFactorySelector
        // (see the class Javadoc). Resolve the key OUTSIDE computeIfAbsent: effectiveApiKey() may read a
        // file (the wizard's 0600 fallback), and blocking I/O inside the CHM callback would pin the
        // carrier thread (CLAUDE.md §3.8 / [M7]). The callback itself stays I/O-free.
        String key = effectiveApiKey();
        return modelsByName.computeIfAbsent(ref.model(), modelName -> GoogleAiGeminiChatModel.builder()
                .apiKey(key)
                .modelName(modelName)
                .timeout(timeout)
                .httpClientBuilder(new JdkHttpClientBuilder()) // explicit: native ServiceLoader is empty (see class javadoc)
                .build());
    }

    /**
     * The API key to use: the configured {@code quarkus.langchain4j.ai.gemini.api-key} when set to a
     * real value (env / {@code -D} / {@code application.properties} keep precedence), otherwise the key
     * the {@code forvum provider add} wizard stored under {@code state/credentials/google} (P2-10 #35).
     * The {@code unset} boot placeholder counts as "no key" so the file fallback applies. Read at
     * resolve time so a just-written key is seen without a restart; the empty string when neither is
     * present (the missing-key error then surfaces at {@code chat()} time as before). Package-private
     * for unit testing.
     */
    String effectiveApiKey() {
        if (apiKey != null && !apiKey.isBlank() && !apiKey.equals(GEMINI_PLACEHOLDER)) {
            return apiKey;
        }
        return FileApiKeyStore.read(FileApiKeyStore.resolveHome(forvumHome), extensionId()).orElse("");
    }
}
