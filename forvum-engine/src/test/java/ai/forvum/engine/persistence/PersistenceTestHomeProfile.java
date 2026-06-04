package ai.forvum.engine.persistence;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Points {@code $FORVUM_HOME} at a throwaway temp directory so the SQLite database resolves to
 * {@code <temp>/state/forvum.sqlite}. Deliberately does NOT pre-create the {@code state/} subdir —
 * that forces {@link StateDirInitializer} to create it before Flyway migrates, exercising the
 * created-later state (M4 lesson) rather than masking the ordering with a pre-populated fixture.
 */
public class PersistenceTestHomeProfile implements QuarkusTestProfile {

    static final Path HOME = createHome();

    private static Path createHome() {
        try {
            return Files.createTempDirectory("forvum-persist-home");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        // Literal key (ForvumHome.HOME_CONFIG_KEY is package-private to ai.forvum.engine.config).
        return Map.of("forvum.home", HOME.toString());
    }
}
