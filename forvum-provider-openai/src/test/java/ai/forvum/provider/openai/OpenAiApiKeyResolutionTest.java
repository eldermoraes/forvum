package ai.forvum.provider.openai;

import ai.forvum.sdk.FileApiKeyStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plain unit tests (no CDI) for {@link OpenAiModelProvider#effectiveApiKey()} — the P2-10 #35
 * config-first / file-fallback precedence. {@code resolve()} itself needs an ArC context and is covered
 * by {@code OpenAiModelProviderTest} (@QuarkusTest); the key-selection logic is pure and tested here.
 */
class OpenAiApiKeyResolutionTest {

    private OpenAiModelProvider providerWith(String configKey, Path home) {
        OpenAiModelProvider provider = new OpenAiModelProvider();
        provider.apiKey = configKey;
        provider.forvumHome = Optional.of(home.toString());
        return provider;
    }

    @Test
    void usesConfiguredKeyWhenSet(@TempDir Path home) {
        FileApiKeyStore.store(home, "openai", "sk-from-file");
        assertEquals("sk-config", providerWith("sk-config", home).effectiveApiKey());
    }

    @Test
    void fallsBackToStoredFileWhenConfigBlank(@TempDir Path home) {
        FileApiKeyStore.store(home, "openai", "sk-from-file");
        assertEquals("sk-from-file", providerWith("", home).effectiveApiKey());
    }

    @Test
    void emptyWhenNeitherConfiguredNorStored(@TempDir Path home) {
        assertEquals("", providerWith("", home).effectiveApiKey());
    }
}
