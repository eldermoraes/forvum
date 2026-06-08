package ai.forvum.engine.session.compaction;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Points {@code $FORVUM_HOME} at a throwaway temp directory so the SQLite database resolves to
 * {@code <temp>/state/forvum.sqlite} for the compaction IT. Each compaction test writes rows directly
 * into the schema and scopes its assertions to its own session, so the shared-DB pollution rule
 * (CLAUDE.md section 14) is satisfied by per-session keys.
 */
public class CompactionTestHomeProfile implements QuarkusTestProfile {

    static final Path HOME = createHome();

    private static Path createHome() {
        try {
            return Files.createTempDirectory("forvum-compaction-home");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("forvum.home", HOME.toString());
    }
}
