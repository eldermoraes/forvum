package ai.forvum.engine.memory;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Drives {@link MemoryWriter} deterministically: a throwaway home, the scripted extraction model
 * ({@code scripted-fact}), and the deterministic {@code fake-embed} embedding model — no live Ollama.
 */
public class MemoryWriterTestProfile implements QuarkusTestProfile {

    static final Path HOME = createHome();

    private static Path createHome() {
        try {
            return Files.createTempDirectory("forvum-memwriter-home");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "forvum.home", HOME.toString(),
                "forvum.memory.extraction-model", "scripted-fact:x",
                "forvum.memory.embedding-model", "fake-embed:x");
    }
}
