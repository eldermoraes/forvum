package ai.forvum.channel.signal;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code SignalChannel.onStart} honors the graceful no-config boot contract (the M4/M17/P2-CH rule): an
 * absent {@code channels/signal.json} (disabled), an enabled-but-coordinate-less config, and a config
 * missing only one of {@code baseUrl}/{@code account} all log + no-op — NO stream connection is
 * attempted, nothing thrown — so the CI native no-config smoke (no {@code ~/.forvum/}) boots and exits
 * cleanly. A plain POJO test: the channel is wired by hand (no CDI proxy), and the only side effect
 * under test is whether a worker executor was started.
 */
class SignalChannelBootTest {

    /** Wire a channel pointing at an explicit config file (no CDI). The api/processor stay null on
     *  purpose: the disabled/credential-less branches must return BEFORE ever touching them. */
    private static SignalChannel wiredChannel(Path configFile) {
        SignalChannel channel = new SignalChannel();
        channel.config = new SignalChannelConfig(configFile);
        return channel;
    }

    @Test
    void absentConfigLeavesTheChannelUnconnectedWithoutThrowing() {
        SignalChannel channel = wiredChannel(Path.of("/nonexistent/signal.json"));

        channel.onStart(null);

        assertNull(channel.worker, "a disabled channel never starts the stream worker");
        // onStop must be safe even though no worker was started.
        channel.onStop(null);
    }

    @Test
    void enabledButCoordinateLessConfigLeavesTheChannelUnconnected(@TempDir Path home)
            throws IOException {
        SignalChannel channel = wiredChannel(write(home, "{ \"enabled\": true }"));

        channel.onStart(null);

        assertNull(channel.worker, "no baseUrl AND no account → warn + no-op");
        channel.onStop(null);
    }

    @Test
    void aBaseUrlWithoutAnAccountLeavesTheChannelUnconnected(@TempDir Path home) throws IOException {
        SignalChannel channel =
                wiredChannel(write(home, "{ \"baseUrl\": \"http://localhost:8080\" }"));

        channel.onStart(null);

        assertNull(channel.worker, "baseUrl without account → warn + no-op (both keys are required)");
        channel.onStop(null);
    }

    @Test
    void anAccountWithoutABaseUrlLeavesTheChannelUnconnected(@TempDir Path home) throws IOException {
        SignalChannel channel = wiredChannel(write(home, "{ \"account\": \"+15559990000\" }"));

        channel.onStart(null);

        assertNull(channel.worker, "account without baseUrl → warn + no-op (both keys are required)");
        channel.onStop(null);
    }

    private static Path write(Path home, String json) throws IOException {
        Path channels = Files.createDirectories(home.resolve("channels"));
        Path file = channels.resolve("signal.json");
        Files.writeString(file, json);
        return file;
    }
}
