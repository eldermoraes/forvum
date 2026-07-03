package ai.forvum.engine.memory;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Isolates the local-memory ITs on a throwaway temp {@code $FORVUM_HOME} and pins the embedding model to
 * the deterministic {@code fake-embed} provider, so the cosine ranking is reproducible with no live Ollama.
 */
public class LocalMemoryTestProfile implements QuarkusTestProfile {

    static final Path HOME = createHome();

    private static Path createHome() {
        try {
            return Files.createTempDirectory("forvum-localmem-home");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "forvum.home", HOME.toString(),
                "forvum.memory.embedding-model", "fake-embed:test");
    }
}
