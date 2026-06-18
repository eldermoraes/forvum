package ai.forvum.provider.anthropic;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;
import ai.forvum.sdk.FileApiKeyStore;
import ai.forvum.sdk.ForvumExtension;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Anthropic model provider. Resolves any {@code anthropic:<model>} ModelRef to a LangChain4j
 * ChatModel built programmatically, so a single bean serves every Anthropic model.
 *
 * <p>The API key is read from config ({@code quarkus.langchain4j.anthropic.api-key}, default empty)
 * — "fixed code, configurable behavior" (CLAUDE.md §1): an operator sets the key in config without
 * recompiling. The default is empty so the bean starts with no key at the native boot smoke
 * (no {@code ~/.forvum/}); the key is required only when {@code chat()} is called. When the config
 * key is blank the provider falls back to the file the {@code forvum provider add} wizard stored
 * under {@code state/credentials/anthropic} (P2-10 #35, see {@link #effectiveApiKey()}).
 *
 * <p>AnthropicChatModel construction is lazy — the underlying Quarkus Reactive REST Client is built
 * when the first {@link #resolve} call is made, not at bean startup — so this bean starts cleanly
 * with no live Anthropic service. Built models are cached per model id: each build allocates an
 * underlying HTTP client, so re-resolving the same {@code anthropic:<model>} on every turn would
 * churn clients; the cache reuses one model per id.
 *
 * <p>Note: unlike OllamaChatModel (plain HTTP), {@code AnthropicChatModel} from the Quarkiverse
 * extension uses a Quarkus Reactive REST Client ({@code QuarkusAnthropicClient}) which requires an
 * active ArC CDI context at {@code build()} time. The {@link #resolve} method is therefore only
 * callable within a live CDI context (e.g. from a {@code @QuarkusTest} or the running application),
 * never from a plain unit test without CDI.
 */
@ForvumExtension
@ApplicationScoped
public class AnthropicModelProvider extends AbstractModelProvider {

    /** Anthropic API key; package-private for ArC field injection. */
    @ConfigProperty(name = "quarkus.langchain4j.anthropic.api-key", defaultValue = "")
    String apiKey;

    /**
     * {@code $FORVUM_HOME} for the file-backed key fallback (P2-10 #35): when {@link #apiKey} is blank
     * the provider reads {@code state/credentials/anthropic} — the {@code 0600} file the
     * {@code forvum provider add} wizard writes. Package-private for ArC field injection; an absent
     * value resolves to {@code ~/.forvum}.
     */
    @ConfigProperty(name = "forvum.home")
    Optional<String> forvumHome;

    /**
     * HTTP request timeout for programmatic model construction; package-private for ArC field
     * injection. Uses a Forvum-namespaced key ({@code forvum.provider.anthropic.timeout}) to avoid a
     * real conflict with the Quarkiverse extension's own {@code quarkus.langchain4j.anthropic.timeout}
     * binding: that key uses expression interpolation referencing {@code quarkus.langchain4j.timeout},
     * which is absent in Forvum's config, causing a {@code SRCFG00011} boot error if read directly via
     * {@code @ConfigProperty}. The Forvum key is independent and safe. Defaults to 30 s.
     */
    @ConfigProperty(name = "forvum.provider.anthropic.timeout", defaultValue = "30S")
    Duration timeout;

    private final ConcurrentMap<String, ChatModel> modelsByName = new ConcurrentHashMap<>();

    @Override
    public String extensionId() {
        return "anthropic";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        // build() requires an active ArC context (QuarkusAnthropicClient uses Reactive REST Client).
        // Resolve the key OUTSIDE computeIfAbsent: effectiveApiKey() may read a file (the wizard's 0600
        // fallback), and blocking I/O inside the CHM callback would hold the bin monitor and pin the
        // carrier thread (CLAUDE.md §3.8 / [M7]). The callback itself stays I/O-free.
        String key = effectiveApiKey();
        return modelsByName.computeIfAbsent(ref.model(), modelName -> AnthropicChatModel.builder()
                .apiKey(key)
                .modelName(modelName)
                .timeout(timeout)
                .build());
    }

    /**
     * The API key to use: the configured {@code quarkus.langchain4j.anthropic.api-key} when set (env /
     * {@code -D} / {@code application.properties} keep precedence), otherwise the key the
     * {@code forvum provider add} wizard stored under {@code state/credentials/anthropic} (P2-10 #35).
     * Read at resolve time so a just-written key is seen without a restart; the empty string when
     * neither is present (the missing-key error then surfaces at {@code chat()} time as before).
     * Package-private for unit testing.
     */
    String effectiveApiKey() {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        return FileApiKeyStore.read(FileApiKeyStore.resolveHome(forvumHome), extensionId()).orElse("");
    }
}
