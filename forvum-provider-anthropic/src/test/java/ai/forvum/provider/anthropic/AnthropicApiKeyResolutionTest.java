package ai.forvum.provider.anthropic;

import ai.forvum.sdk.FileApiKeyStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plain unit tests (no CDI) for {@link AnthropicModelProvider#effectiveApiKey()} — the P2-10 #35
 * config-first / file-fallback precedence. {@code resolve()} itself needs an ArC context and is covered
 * by {@code AnthropicModelProviderTest} (@QuarkusTest); the key-selection logic is pure and tested here.
 */
class AnthropicApiKeyResolutionTest {

    private AnthropicModelProvider providerWith(String configKey, Path home) {
        AnthropicModelProvider provider = new AnthropicModelProvider();
        provider.apiKey = configKey;
        provider.forvumHome = Optional.of(home.toString());
        return provider;
    }

    @Test
    void usesConfiguredKeyWhenSet(@TempDir Path home) {
        // A stored file must NOT override an explicitly-configured key (env / -D precedence).
        FileApiKeyStore.store(home, "anthropic", "sk-from-file");
        assertEquals("sk-config", providerWith("sk-config", home).effectiveApiKey());
    }

    @Test
    void fallsBackToStoredFileWhenConfigBlank(@TempDir Path home) {
        FileApiKeyStore.store(home, "anthropic", "sk-from-file");
        assertEquals("sk-from-file", providerWith("", home).effectiveApiKey());
    }

    @Test
    void whitespaceOnlyConfigKey_fallsBackToStoredFile(@TempDir Path home) {
        // The guard is isBlank() (NOT isEmpty()) by design: a trailing-space env key (e.g.
        // QUARKUS_LANGCHAIN4J_ANTHROPIC_API_KEY="   ") is "no key" and falls back to the stored file.
        // Red-checks the isBlank()-not-isEmpty() decision: a mutation to isEmpty() returns "   " here.
        FileApiKeyStore.store(home, "anthropic", "sk-from-file");
        assertEquals("sk-from-file", providerWith("   ", home).effectiveApiKey());
    }

    @Test
    void emptyWhenNeitherConfiguredNorStored(@TempDir Path home) {
        assertEquals("", providerWith("", home).effectiveApiKey());
    }
}
