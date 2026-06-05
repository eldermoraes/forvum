package ai.forvum.provider.anthropic;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;
import ai.forvum.sdk.ForvumExtension;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Anthropic model provider. Resolves any {@code anthropic:<model>} ModelRef to a LangChain4j
 * ChatModel built programmatically, so a single bean serves every Anthropic model.
 *
 * <p>The API key is read from config ({@code quarkus.langchain4j.anthropic.api-key}, default empty)
 * — "fixed code, configurable behavior" (CLAUDE.md §1): an operator sets the key in config without
 * recompiling. The default is empty so the bean starts with no key at the native boot smoke
 * (no {@code ~/.forvum/}); the key is required only when {@code chat()} is called.
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
     * HTTP request timeout for programmatic model construction; package-private for ArC field
     * injection. Uses a Forvum-namespaced key to avoid conflicting with the Quarkiverse extension's
     * own {@code quarkus.langchain4j.anthropic.timeout} binding (which the extension manages for
     * its declarative {@code @RegisterAiService} path). Defaults to 30 s.
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
        // The CHM computeIfAbsent callback is synchronous and short (no I/O at build time), so it does
        // not pin the carrier thread — only the CDI context check inside the REST client builder runs.
        return modelsByName.computeIfAbsent(ref.model(), modelName -> AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(timeout)
                .build());
    }
}
