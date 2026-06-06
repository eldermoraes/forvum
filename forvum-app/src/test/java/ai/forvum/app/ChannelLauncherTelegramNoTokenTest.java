package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertFalse;

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
 * An enabled {@code channels/telegram.json} with NO {@code botToken} must NOT select server mode:
 * {@code TelegramChannel.onStart} warns and no-ops without a token, so the binary would otherwise hang in
 * server mode serving nothing. This guards the token-aware {@link ChannelLauncher#serves} logic against a
 * regression. The token-present case is covered by {@link ChannelLauncherTelegramServerModeTest}.
 */
@QuarkusTest
@TestProfile(ChannelLauncherTelegramNoTokenTest.TelegramNoTokenHomeProfile.class)
class ChannelLauncherTelegramNoTokenTest {

    @Inject
    ChannelLauncher launcher;

    @Test
    void anEnabledTelegramChannelWithoutATokenStaysInCommandMode() {
        assertFalse(launcher.shouldRunAsServer(),
                "an enabled but token-less channels/telegram.json must not keep the binary alive");
    }

    /** Seeds {@code $FORVUM_HOME} with an enabled telegram channel config that has no botToken. */
    public static class TelegramNoTokenHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-launch-home-tg-notoken");
                Path channels = Files.createDirectories(home.resolve("channels"));
                Files.writeString(channels.resolve("telegram.json"), "{ \"enabled\": true }");
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
