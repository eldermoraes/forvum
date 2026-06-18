package ai.forvum.provider.google;

import ai.forvum.sdk.FileApiKeyStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plain unit tests (no CDI) for {@link GoogleModelProvider#effectiveApiKey()} — the P2-10 #35
 * config-first / file-fallback precedence, plus the {@code unset} boot-placeholder special case.
 * {@code resolve()} itself needs an ArC context and is covered by {@code GoogleModelProviderTest}
 * (@QuarkusTest); the key-selection logic is pure and tested here.
 */
class GoogleApiKeyResolutionTest {

    private GoogleModelProvider providerWith(String configKey, Path home) {
        GoogleModelProvider provider = new GoogleModelProvider();
        provider.apiKey = configKey;
        provider.forvumHome = Optional.of(home.toString());
        return provider;
    }

    @Test
    void usesConfiguredKeyWhenSet(@TempDir Path home) {
        FileApiKeyStore.store(home, "google", "AIza-from-file");
        assertEquals("AIza-config", providerWith("AIza-config", home).effectiveApiKey());
    }

    @Test
    void fallsBackToStoredFileWhenConfigBlank(@TempDir Path home) {
        FileApiKeyStore.store(home, "google", "AIza-from-file");
        assertEquals("AIza-from-file", providerWith("", home).effectiveApiKey());
    }

    @Test
    void treatsUnsetPlaceholderAsNoKeyAndFallsBackToFile(@TempDir Path home) {
        // application.properties seeds "unset" so the ai-gemini extension boots without a real key;
        // it must NOT be used as a literal key — the wizard's stored file wins.
        FileApiKeyStore.store(home, "google", "AIza-from-file");
        assertEquals("AIza-from-file", providerWith("unset", home).effectiveApiKey());
    }

    @Test
    void emptyWhenNeitherConfiguredNorStored(@TempDir Path home) {
        assertEquals("", providerWith("", home).effectiveApiKey());
    }
}
