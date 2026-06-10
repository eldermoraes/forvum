package ai.forvum.channel.slack;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code SlackChannel.onStart} honors the graceful no-config boot contract (the M4/M17/P2-CH rule): an
 * absent {@code channels/slack.json} (disabled) and an enabled config missing EITHER token both warn +
 * no-op — NO {@code apps.connections.open} call, NO socket connection, nothing thrown — so the CI native
 * no-config smoke (no {@code ~/.forvum/}) boots and exits cleanly. A plain POJO test: the channel is
 * wired by hand (no CDI proxy), and the only side effect under test is whether a connect executor was
 * started.
 */
class SlackChannelBootTest {

    /** Wire a channel pointing at an explicit config file (no CDI). connectors/rest are left null on
     *  purpose: the disabled/credential-less branches must return BEFORE ever touching them. */
    private static SlackChannel wiredChannel(Path configFile) {
        SlackChannel channel = new SlackChannel();
        channel.config = new SlackChannelConfig(configFile);
        return channel;
    }

    @Test
    void absentConfigLeavesTheChannelUnconnectedWithoutThrowing() {
        SlackChannel channel = wiredChannel(Path.of("/nonexistent/slack.json"));

        channel.onStart(null);

        assertNull(channel.connectExecutor, "a disabled channel never opens a Socket Mode connection");
        // onStop must be safe even though no connection/executor was started.
        channel.onStop(null);
    }

    @Test
    void enabledButMissingAppTokenLeavesTheChannelUnconnectedWithoutThrowing(@TempDir Path home)
            throws IOException {
        Path channels = Files.createDirectories(home.resolve("channels"));
        Files.writeString(channels.resolve("slack.json"),
                "{ \"enabled\": true, \"botToken\": \"xoxb-only\" }");

        SlackChannel channel = wiredChannel(channels.resolve("slack.json"));

        channel.onStart(null);

        assertNull(channel.connectExecutor,
                "a botToken without an appToken cannot open the socket — must no-op");
        channel.onStop(null);
    }

    @Test
    void enabledButMissingBotTokenLeavesTheChannelUnconnectedWithoutThrowing(@TempDir Path home)
            throws IOException {
        Path channels = Files.createDirectories(home.resolve("channels"));
        Files.writeString(channels.resolve("slack.json"),
                "{ \"enabled\": true, \"appToken\": \"xapp-only\" }");

        SlackChannel channel = wiredChannel(channels.resolve("slack.json"));

        channel.onStart(null);

        assertNull(channel.connectExecutor,
                "an appToken without a botToken could not reply — must no-op");
        channel.onStop(null);
    }

    @Test
    void enabledButTokenlessConfigLeavesTheChannelUnconnectedWithoutThrowing(@TempDir Path home)
            throws IOException {
        Path channels = Files.createDirectories(home.resolve("channels"));
        Files.writeString(channels.resolve("slack.json"), "{ \"enabled\": true }");

        SlackChannel channel = wiredChannel(channels.resolve("slack.json"));

        channel.onStart(null);

        assertNull(channel.connectExecutor, "an enabled but token-less channel never connects");
        channel.onStop(null);
    }
}
