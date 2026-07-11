package ai.forvum.tools.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

/** Home resolution for {@link TtsConfig}, mirroring {@code ShellAllowlistHomeTest} / {@code ForvumHome.resolve}. */
class TtsConfigHomeTest {

    @Test
    void usesTheConfiguredHomeWhenPresent() {
        Path resolved = TtsConfig.resolveHome(Optional.of("/srv/forvum"), "/home/alice");

        assertEquals(Path.of("/srv/forvum").toAbsolutePath().normalize(), resolved);
    }

    @Test
    void fallsBackToUserHomeDotForvumWhenUnset() {
        Path resolved = TtsConfig.resolveHome(Optional.empty(), "/home/alice");

        assertEquals(Path.of("/home/alice", ".forvum").toAbsolutePath().normalize(), resolved);
    }

    @Test
    void fallsBackWhenTheConfiguredHomeIsBlank() {
        Path resolved = TtsConfig.resolveHome(Optional.of("   "), "/home/alice");

        assertEquals(Path.of("/home/alice", ".forvum").toAbsolutePath().normalize(), resolved);
    }
}
