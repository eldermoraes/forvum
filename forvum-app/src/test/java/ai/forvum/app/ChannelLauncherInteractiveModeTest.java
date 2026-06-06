package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * With an enabled {@code channels/tui.json} present, the launcher selects interactive foreground mode —
 * the binary runs the TUI REPL in the foreground rather than serving or exiting. The TUI is not a server
 * channel, so {@code shouldRunAsServer()} stays false. Complements {@link ChannelLauncherServerModeTest}.
 */
@QuarkusTest
@TestProfile(ChannelLauncherInteractiveModeTest.TuiEnabledHomeProfile.class)
class ChannelLauncherInteractiveModeTest {

    @Inject
    ChannelLauncher launcher;

    @Test
    void anEnabledTuiChannelSelectsInteractiveMode() {
        assertTrue(launcher.shouldRunInteractive(),
                "an enabled channels/tui.json runs the TUI REPL in the foreground");
        assertFalse(launcher.shouldRunAsServer(),
                "the TUI is a foreground channel, not a server channel");
    }

    /** Seeds {@code $FORVUM_HOME} with an enabled TUI channel config. */
    public static class TuiEnabledHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-tui-launch-home");
                Path channels = Files.createDirectories(home.resolve("channels"));
                Files.writeString(channels.resolve("tui.json"), "{ \"enabled\": true }");
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
