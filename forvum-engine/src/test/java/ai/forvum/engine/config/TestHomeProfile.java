package ai.forvum.engine.config;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Points {@code $FORVUM_HOME} at a throwaway temp directory (with the watched subfolders pre-created,
 * plus a {@code crons/seed.json} for the real-file modify test) so the boot-time {@link ConfigWatcher}
 * watches it instead of the developer's real {@code ~/.forvum/}. The directory must exist before the
 * app boots, so it is created in a static initializer.
 */
public class TestHomeProfile implements QuarkusTestProfile {

    static final Path HOME = createHome();

    private static Path createHome() {
        try {
            Path home = Files.createTempDirectory("forvum-test-home");
            // mcp-servers/ is intentionally absent at boot — ConfigWatcherTest creates it at runtime to
            // exercise dynamic registration of a watched subfolder that did not exist at startup.
            for (String subfolder : List.of("identities", "agents", "skills", "crons", "channels")) {
                Files.createDirectories(home.resolve(subfolder));
            }
            Files.writeString(home.resolve("crons/seed.json"), "{\"schedule\":\"0 0 * * *\"}");
            Files.writeString(home.resolve("identities/seed.json"), "{\"displayName\":\"seed\"}");
            return home;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(ForvumHome.HOME_CONFIG_KEY, HOME.toString());
    }
}
