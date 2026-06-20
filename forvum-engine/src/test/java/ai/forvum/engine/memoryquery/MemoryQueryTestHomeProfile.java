package ai.forvum.engine.memoryquery;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Points {@code $FORVUM_HOME} at a throwaway temp dir so the SQLite DB is isolated for the
 * {@code MemoryQueryService} ITs (separate from the shared persistence-profile DB).
 */
public class MemoryQueryTestHomeProfile implements QuarkusTestProfile {

    static final Path HOME = createHome();

    private static Path createHome() {
        try {
            return Files.createTempDirectory("forvum-memquery-home");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("forvum.home", HOME.toString());
    }
}
