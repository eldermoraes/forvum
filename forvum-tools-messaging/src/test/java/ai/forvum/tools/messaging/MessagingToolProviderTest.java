package ai.forvum.tools.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.ChannelSender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link MessagingToolProvider} contract (#188): it contributes {@code message.send} (CHANNEL_SEND),
 * validates the channel id against the {@link ConfiguredChannels} oracle (unknown channel rejected),
 * resolves the installed {@link ChannelSender} by extension id, delivers through it, and reports an
 * unconfigured sender to the model. Pure unit test with a recording {@code ChannelSender} fixture and a
 * {@code @TempDir}-backed {@code channels/} directory — no engine, no CDI container.
 */
class MessagingToolProviderTest {

    @TempDir
    Path home;

    /** A recording {@link ChannelSender} double. */
    static final class RecordingSender implements ChannelSender {
        final String id;
        final List<String[]> sent = new ArrayList<>();
        boolean configured = true;

        RecordingSender(String id) {
            this.id = id;
        }

        @Override
        public String extensionId() {
            return id;
        }

        @Override
        public boolean send(String target, String text) {
            if (!configured) {
                return false;
            }
            sent.add(new String[] {target, text});
            return true;
        }
    }

    private ConfiguredChannels channelsWith(String... ids) throws IOException {
        Path dir = home.resolve("channels");
        Files.createDirectories(dir);
        for (String id : ids) {
            Files.writeString(dir.resolve(id + ".json"), "{\"enabled\": true}");
        }
        return new ConfiguredChannels(dir);
    }

    @Test
    void reportsTheMessagingExtensionId() throws IOException {
        assertEquals("messaging",
                new MessagingToolProvider(channelsWith(), List.<ChannelSender>of()).extensionId());
    }

    @Test
    void contributesMessageSendGatedByChannelSend() throws IOException {
        List<ToolSpec> tools =
                new MessagingToolProvider(channelsWith(), List.<ChannelSender>of()).tools();
        assertEquals(1, tools.size());

        ToolSpec spec = tools.getFirst();
        assertEquals("message.send", spec.name());
        assertEquals(PermissionScope.CHANNEL_SEND, spec.requiredScope());
        assertFalse(spec.userConfirmRequired(),
                "message.send is RBAC/belt-gated but not approval-gated (issue #188)");
    }

    @Test
    void sendDeliversThroughTheMatchingSender() throws IOException {
        RecordingSender telegram = new RecordingSender("telegram");
        RecordingSender other = new RecordingSender("discord");
        MessagingToolProvider provider = new MessagingToolProvider(
                channelsWith("telegram", "discord"), List.of(other, telegram));

        String result = provider.invoke("message.send",
                Map.of("channelId", "telegram", "target", "42", "text", "hello"));

        assertEquals(1, telegram.sent.size(), "the send reaches the matching sender");
        assertEquals("42", telegram.sent.getFirst()[0]);
        assertEquals("hello", telegram.sent.getFirst()[1]);
        assertTrue(other.sent.isEmpty(), "a non-matching sender is never touched");
        assertTrue(result.toLowerCase().contains("sent"), "the model gets a delivery confirmation");
    }

    @Test
    void sendWithoutTargetUsesTheChannelDefaultDestination() throws IOException {
        RecordingSender telegram = new RecordingSender("telegram");
        MessagingToolProvider provider =
                new MessagingToolProvider(channelsWith("telegram"), List.of(telegram));

        String result = provider.invoke("message.send",
                Map.of("channelId", "telegram", "text", "ping"));

        assertEquals("", telegram.sent.getFirst()[0], "an absent target is passed as blank (channel default)");
        assertTrue(result.contains("default destination"));
    }

    @Test
    void unknownChannelIsRejectedWithoutTouchingAnySender() throws IOException {
        RecordingSender telegram = new RecordingSender("telegram");
        MessagingToolProvider provider =
                new MessagingToolProvider(channelsWith("telegram"), List.of(telegram));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("message.send",
                        Map.of("channelId", "matrix", "text", "x")));

        assertTrue(e.getMessage().contains("Unknown channel 'matrix'"));
        assertTrue(telegram.sent.isEmpty(), "an unknown channel never reaches a sender");
    }

    @Test
    void configuredChannelWithNoInstalledSenderIsRejected() throws IOException {
        MessagingToolProvider provider =
                new MessagingToolProvider(channelsWith("tui"), List.<ChannelSender>of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("message.send", Map.of("channelId", "tui", "text", "x")));
        assertTrue(e.getMessage().contains("no installed outbound sender"));
    }

    @Test
    void unconfiguredSenderYieldsAnActionableNotSentReport() throws IOException {
        RecordingSender telegram = new RecordingSender("telegram");
        telegram.configured = false;
        MessagingToolProvider provider =
                new MessagingToolProvider(channelsWith("telegram"), List.of(telegram));

        String result = provider.invoke("message.send",
                Map.of("channelId", "telegram", "text", "x"));

        assertTrue(result.contains("NOT sent"), "the model must not be told an undelivered message was sent");
        assertTrue(result.contains("channels/telegram.json"), "the report names the config file to fix");
    }

    @Test
    void invokeRejectsAnUnknownToolName() throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> new MessagingToolProvider(channelsWith(), List.<ChannelSender>of())
                        .invoke("message.broadcast", Map.of()));
    }

    @Test
    void invokeRejectsAMissingRequiredArgument() throws IOException {
        MessagingToolProvider provider =
                new MessagingToolProvider(channelsWith("telegram"), List.<ChannelSender>of());
        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("message.send", Map.of("channelId", "telegram")));
        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("message.send", Map.of("text", "x")));
    }
}
