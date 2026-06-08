package ai.forvum.channel.discord;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code DiscordChannel.onStart} honors the graceful no-config boot contract (the M4/M17/P2-CH rule): an
 * absent {@code channels/discord.json} (disabled) and an enabled-but-tokenless config both warn + no-op —
 * NO gateway connection is attempted, nothing thrown — so the CI native no-config smoke (no
 * {@code ~/.forvum/}) boots and exits cleanly. A plain POJO test: the channel is wired by hand (no CDI
 * proxy), and the only side effect under test is whether a connect executor was started.
 */
class DiscordChannelBootTest {

    /** Wire a channel pointing at an explicit config file (no CDI). connectors is left null on purpose:
     *  the disabled/tokenless branches must return BEFORE ever touching the connector. */
    private static DiscordChannel wiredChannel(Path configFile) {
        DiscordChannel channel = new DiscordChannel();
        channel.config = new DiscordChannelConfig(configFile);
        channel.gatewayUrl = "wss://gateway.discord.gg/?v=10&encoding=json";
        return channel;
    }

    @Test
    void absentConfigLeavesTheChannelUnconnectedWithoutThrowing() {
        DiscordChannel channel = wiredChannel(Path.of("/nonexistent/discord.json"));

        channel.onStart(null);

        assertNull(channel.connectExecutor, "a disabled channel never opens a gateway connection");
        // onStop must be safe even though no connection/executor was started.
        channel.onStop(null);
    }

    @Test
    void enabledButTokenlessConfigLeavesTheChannelUnconnectedWithoutThrowing(@TempDir Path home)
            throws IOException {
        Path channels = Files.createDirectories(home.resolve("channels"));
        Files.writeString(channels.resolve("discord.json"), "{ \"enabled\": true }");

        DiscordChannel channel = wiredChannel(channels.resolve("discord.json"));

        channel.onStart(null);

        assertNull(channel.connectExecutor, "an enabled but token-less channel never opens a connection");
        channel.onStop(null);
    }
}
