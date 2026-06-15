package ai.forvum.provider.copilot;

import ai.forvum.core.ModelRef;
import ai.forvum.provider.copilot.CopilotAuth.CopilotToken;
import ai.forvum.sdk.AbstractModelProvider;
import ai.forvum.sdk.ForvumExtension;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * GitHub Copilot model provider (#42). Resolves any {@code copilot:<model>} ModelRef to a LangChain4j
 * {@code OpenAiChatModel} (Copilot is OpenAI-compatible) pointed at the proxy-ep-derived Copilot endpoint,
 * authenticated with the short-lived Copilot token that {@link CopilotCredentials} exchanges from the stored
 * GitHub token, and carrying the IDE headers GitHub Copilot expects.
 *
 * <p><strong>HTTP client (native):</strong> {@code OpenAiChatModel.builder()} is the Quarkiverse-SWAPPED
 * builder (it uses the Quarkus REST client, like the OpenAI/Anthropic providers), so it is native-safe with
 * NO explicit {@code httpClientBuilder} pin and never reaches the langchain4j {@code HttpClientBuilderLoader}
 * ServiceLoader (unlike Ollama/Gemini). It does require an active ArC context at {@code build()} time, which
 * a turn always has. The model is cached per {@code (model, token)} so a stable Copilot token reuses one
 * model and a refreshed token rebuilds (the token is baked into the model at build time).
 *
 * <p>Unlike the offline providers, {@link #resolve} needs the (cached) Copilot token, whose first exchange is
 * a network call — so it is exercised by the module tests (build path + missing-credentials), not by the
 * app-level {@code ProviderResolveInAppClasspathTest} offline guard. {@link CopilotModelBuildTest} proves the
 * build path with Copilot's custom base URL + IDE headers.
 */
@ForvumExtension
@ApplicationScoped
public class CopilotModelProvider extends AbstractModelProvider {

    @Inject
    CopilotCredentials credentials;

    /** Cached model per id, with the token it was built with, so a token refresh rebuilds. */
    private record Cached(String tokenId, ChatModel model) {
    }

    private final ConcurrentMap<String, Cached> modelsByName = new ConcurrentHashMap<>();

    @Override
    public String extensionId() {
        return "copilot";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        CopilotToken token = credentials.currentApiToken(); // cached; exchanges only on first use / expiry
        Cached cached = modelsByName.get(ref.model());
        if (cached != null && cached.tokenId().equals(token.token())) {
            return cached.model();
        }
        ChatModel model = buildModel(ref.model(), token); // lazy build, no network
        modelsByName.put(ref.model(), new Cached(token.token(), model));
        return model;
    }

    /**
     * Build the OpenAI-compatible model for {@code modelName} against {@code token}'s endpoint. The build is
     * lazy (no network) but requires an active ArC context (the Quarkus-swapped OpenAI builder). Package-
     * private so {@link CopilotModelBuildTest} can prove the build path without a live token exchange.
     */
    static ChatModel buildModel(String modelName, CopilotToken token) {
        return OpenAiChatModel.builder()
                .baseUrl(token.baseUrl())
                .apiKey(token.token())
                .modelName(modelName)
                .customHeaders(CopilotAuth.ideHeaders(false))
                .build();
    }
}
