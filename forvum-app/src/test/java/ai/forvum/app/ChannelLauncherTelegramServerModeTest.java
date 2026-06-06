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
 * The {@code "telegram"} arm of {@link ChannelLauncher#shouldRunAsServer()}: an enabled
 * {@code channels/telegram.json} that carries a non-blank {@code botToken} selects server mode (the
 * long-poll loop will serve), end-to-end through the engine's {@code ChannelReader}. The complementary
 * "enabled but token-less => command mode" case is covered by
 * {@link ChannelLauncherTelegramNoTokenTest}.
 */
@QuarkusTest
@TestProfile(ChannelLauncherTelegramServerModeTest.TelegramTokenHomeProfile.class)
class ChannelLauncherTelegramServerModeTest {

    @Inject
    ChannelLauncher launcher;

    @Test
    void anEnabledTelegramChannelWithATokenSelectsServerMode() {
        assertTrue(launcher.shouldRunAsServer(),
                "an enabled channels/telegram.json with a botToken keeps the binary alive to serve");
    }

    /** Seeds {@code $FORVUM_HOME} with an enabled telegram channel config carrying a botToken. */
    public static class TelegramTokenHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-launch-home-tg-token");
                Path channels = Files.createDirectories(home.resolve("channels"));
                Files.writeString(channels.resolve("telegram.json"),
                        "{ \"enabled\": true, \"botToken\": \"t:abc\" }");
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
