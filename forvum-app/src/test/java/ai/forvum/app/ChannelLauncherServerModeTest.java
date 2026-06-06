package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * With an enabled {@code channels/web.json} present, the launcher selects server mode — the binary must
 * stay alive (vertx-http already serves the WebSocket endpoint) rather than print the banner and exit.
 * The complementary command-mode path (no server channel → exit 0) is covered by
 * {@link ForvumApplicationTest}.
 */
@QuarkusTest
@TestProfile(ChannelLauncherServerModeTest.WebEnabledHomeProfile.class)
class ChannelLauncherServerModeTest {

    @Inject
    ChannelLauncher launcher;

    @Test
    void anEnabledWebChannelSelectsServerMode() {
        assertTrue(launcher.shouldRunAsServer(),
                "an enabled channels/web.json keeps the binary alive to serve");
    }

    /** Seeds {@code $FORVUM_HOME} with an enabled web channel config. */
    public static class WebEnabledHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-launch-home");
                Path channels = Files.createDirectories(home.resolve("channels"));
                Files.writeString(channels.resolve("web.json"), "{ \"enabled\": true }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", HOME.toString());
        }
    }
}
