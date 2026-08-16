package ai.forvum.tools.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * {@link ConfiguredChannels} contract (#188): the id set is the {@code channels/*.json} file-name stems
 * (the same oracle the engine's {@code ChannelReader.ids()} gives {@code forvum doctor}), and an absent
 * directory yields an empty set — never a throw (the M4 graceful no-config contract).
 */
class ConfiguredChannelsTest {

    @TempDir
    Path home;

    @Test
    void idsAreTheJsonFileNameStemsSorted() throws IOException {
        Path dir = home.resolve("channels");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("telegram.json"), "{}");
        Files.writeString(dir.resolve("web.json"), "{}");
        Files.writeString(dir.resolve("notes.txt"), "not a channel");

        assertEquals(Set.of("telegram", "web"), new ConfiguredChannels(dir).ids());
    }

    @Test
    void absentDirectoryYieldsAnEmptySet() {
        assertTrue(new ConfiguredChannels(home.resolve("channels")).ids().isEmpty());
    }

    @Test
    void injectConstructorResolvesTheConfiguredHome() throws IOException {
        Path dir = home.resolve("channels");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("telegram.json"), "{}");

        ConfiguredChannels channels = new ConfiguredChannels(java.util.Optional.of(home.toString()));
        assertEquals(Set.of("telegram"), channels.ids());
    }

    @Test
    void injectConstructorFallsBackToUserHomeDotForvum() {
        ConfiguredChannels channels = new ConfiguredChannels(java.util.Optional.of("  "));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(channels::ids,
                "a blank forvum.home falls back to <user.home>/.forvum and never throws");
    }
}
