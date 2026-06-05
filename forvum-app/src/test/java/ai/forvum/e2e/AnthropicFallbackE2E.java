package ai.forvum.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ModelRef;
import ai.forvum.engine.model.FailureClassifier;
import ai.forvum.engine.model.FallbackChatModel;
import ai.forvum.engine.model.FallbackLink;
import ai.forvum.engine.model.ProviderCallRecorder;
import ai.forvum.engine.persistence.ProviderCallEntity;
import ai.forvum.provider.anthropic.AnthropicModelProvider;
import ai.forvum.provider.ollama.OllamaModelProvider;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * M10 fallback acceptance criterion: an invalid Anthropic key falls through {@link FallbackChatModel}
 * to Ollama.
 *
 * <p>This test realizes the M10 fallback AC by constructing a {@link FallbackChatModel} DIRECTLY —
 * injecting {@link AnthropicModelProvider} (with a bad/empty key) as the primary link and
 * {@link OllamaModelProvider} as the fallback link. Persona multi-link / {@code FallbackChain} is
 * DR-4c (deferred, ULTRAPLAN section 4.3.5.3) — the persona-integrated path is NOT touched here.
 *
 * <p>Expected behavior:
 * <ol>
 *   <li>The Anthropic call fails (invalid key → retryable or non-retryable exception from the Anthropic
 *       API).</li>
 *   <li>Ollama is the fallback link and produces a non-empty reply.</li>
 *   <li>Two {@code provider_calls} rows are recorded: one anthropic failure + one ollama success.</li>
 * </ol>
 *
 * <p><strong>Requires local Ollama:</strong> {@code ollama serve} with {@code qwen3:1.7b} pulled.
 * Anthropic deliberately receives an intentionally empty API key ({@code "bad-key"}) to trigger the
 * failure path without consuming live quota. An empty/invalid key causes an authentication failure
 * that the Anthropic HTTP client wraps in a LangChain4j exception — the exact type (retryable vs.
 * non-retryable) determines whether fallback advances or re-throws. If Anthropic's client wraps auth
 * failures as non-retryable, the fallback chain will NOT advance and the test will surface the auth
 * exception. In that scenario the test is still valuable as a compile-time / wiring check.
 *
 * <p>{@code @Tag("live")} — excluded from the default build. To run manually:
 * <pre>{@code
 *   ./mvnw -pl forvum-app test -Dgroups=live -DexcludedGroups= \
 *       -Dquarkus.langchain4j.ollama.devservices.enabled=false
 * }</pre>
 *
 * <p>NOT {@code @Transactional}: {@link ProviderCallRecorder#record} owns its own transaction; wrapping
 * here would fight that boundary.
 */
@QuarkusTest
@TestProfile(AnthropicFallbackE2E.FallbackHomeProfile.class)
@Tag("live")
class AnthropicFallbackE2E {

    @Inject
    AnthropicModelProvider anthropicProvider;

    @Inject
    OllamaModelProvider ollamaProvider;

    @Inject
    FailureClassifier classifier;

    @Inject
    ProviderCallRecorder recorder;

    @Test
    void invalidAnthropicKeyFallsBackToOllama() {
        ModelRef anthropicRef = ModelRef.parse("anthropic:claude-opus-4-6");
        ModelRef ollamaRef = ModelRef.parse("ollama:qwen3:1.7b");

        // Resolve models before constructing the chain: OllamaChatModel and AnthropicChatModel build
        // lazily (no connection opened), so computeIfAbsent does not run IO inside the CHM bin.
        ChatModel anthropicModel = anthropicProvider.resolve(anthropicRef);
        ChatModel ollamaModel = ollamaProvider.resolve(ollamaRef);

        FallbackLink anthropicLink = new FallbackLink(anthropicRef, anthropicModel, null);
        FallbackLink ollamaLink = new FallbackLink(ollamaRef, ollamaModel, null);

        FallbackChatModel chain = new FallbackChatModel(
                List.of(anthropicLink, ollamaLink),
                "e2e-anthropic-fallback",
                "test-agent",
                classifier,
                recorder,
                null);

        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("Say hi in one word."))
                .build();

        ChatResponse response = chain.chat(request);

        String reply = response.aiMessage().text();
        assertFalse(reply == null || reply.isBlank(), "Ollama fallback must return a non-empty reply");

        long anthropicFailureRows = ProviderCallEntity.count(
                "provider = ?1 and error is not null", "anthropic");
        assertTrue(anthropicFailureRows >= 1,
                "provider_calls must have at least one anthropic failure row, found: " + anthropicFailureRows);

        long ollamaSuccessRows = ProviderCallEntity.count(
                "provider = ?1 and error is null", "ollama");
        assertTrue(ollamaSuccessRows >= 1,
                "provider_calls must have at least one ollama success row, found: " + ollamaSuccessRows);
    }

    /**
     * Points {@code $FORVUM_HOME} at a throwaway temp directory with a minimal agent spec.
     * The Anthropic API key defaults to empty (no-key), triggering the failure path.
     */
    public static class FallbackHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-live-anthropic-fallback-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"anthropic:claude-opus-4-6\","
                      + " \"allowedTools\": [] }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            // Empty API key intentionally triggers the Anthropic failure path.
            return Map.of(
                    "forvum.home", HOME.toString(),
                    "quarkus.langchain4j.anthropic.api-key", "bad-key");
        }
    }
}
