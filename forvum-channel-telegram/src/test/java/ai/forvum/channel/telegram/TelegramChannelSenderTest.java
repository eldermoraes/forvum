package ai.forvum.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.telegram.TelegramChannelConfig.Spec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * {@link TelegramChannelSender} contract (#188): it delivers an outbound text through the M17
 * {@code sendMessage} REST path, resolves the destination chat (explicit target → {@code defaultChatId}
 * → sole allowed user), returns {@code false} (never throws) when the channel is not configured to
 * send, rejects a malformed target, and never leaks the bot token on a transport failure. Pure unit
 * test with the recording bot-api double and a {@code @TempDir}-backed config file.
 */
class TelegramChannelSenderTest {

    @TempDir
    Path home;

    private TelegramChannelSender senderWith(RecordingTelegramBotApi api, String configJson)
            throws IOException {
        Path file = home.resolve("channels").resolve("telegram.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, configJson);
        return new TelegramChannelSender(api, new TelegramChannelConfig(file));
    }

    @Test
    void reportsTheTelegramExtensionId() {
        assertEquals("telegram", new TelegramChannelSender().extensionId());
    }

    @Test
    void sendsToTheExplicitTargetChat() throws IOException {
        RecordingTelegramBotApi api = new RecordingTelegramBotApi();
        TelegramChannelSender sender = senderWith(api,
                "{\"enabled\": true, \"botToken\": \"tok\", \"allowAllUsers\": true}");

        assertTrue(sender.send("42", "hello"));
        assertEquals(1, api.sent.size());
        assertEquals(42L, api.sent.getFirst().chatId());
        assertEquals("hello", api.sent.getFirst().text());
    }

    @Test
    void blankTargetFallsBackToTheConfiguredDefaultChatId() throws IOException {
        RecordingTelegramBotApi api = new RecordingTelegramBotApi();
        TelegramChannelSender sender = senderWith(api,
                "{\"enabled\": true, \"botToken\": \"tok\", \"defaultChatId\": 7, \"allowAllUsers\": true}");

        assertTrue(sender.send("", "ping"));
        assertEquals(7L, api.sent.getFirst().chatId());
    }

    @Test
    void blankTargetFallsBackToTheSoleAllowedUserId() {
        Spec spec = new Spec(true, Optional.of("tok"), Set.of(99L), false);
        assertEquals(Optional.of(99L), TelegramChannelSender.resolveChatId(null, spec));
    }

    @Test
    void blankTargetWithNoDefaultDestinationReturnsFalseWithoutSending() throws IOException {
        RecordingTelegramBotApi api = new RecordingTelegramBotApi();
        TelegramChannelSender sender = senderWith(api,
                "{\"enabled\": true, \"botToken\": \"tok\", \"allowAllUsers\": true}");

        assertFalse(sender.send(null, "ping"), "no target and no default destination — not configured");
        assertTrue(api.sent.isEmpty());
    }

    @Test
    void unconfiguredChannelReturnsFalseWithoutSending() throws IOException {
        RecordingTelegramBotApi api = new RecordingTelegramBotApi();
        TelegramChannelSender noToken = senderWith(api, "{\"enabled\": true}");
        assertFalse(noToken.send("42", "x"), "an enabled-but-token-less channel cannot send");

        TelegramChannelSender absent = new TelegramChannelSender(api,
                new TelegramChannelConfig(home.resolve("channels").resolve("absent.json")));
        assertFalse(absent.send("42", "x"), "an absent channels/telegram.json cannot send");
        assertTrue(api.sent.isEmpty());
    }

    @Test
    void malformedTargetIsRejected() throws IOException {
        TelegramChannelSender sender = senderWith(new RecordingTelegramBotApi(),
                "{\"enabled\": true, \"botToken\": \"tok\", \"allowAllUsers\": true}");
        assertThrows(IllegalArgumentException.class, () -> sender.send("not-a-chat-id", "x"));
    }

    @Test
    void transportFailureNeverLeaksTheBotToken() throws IOException {
        RecordingTelegramBotApi api = new RecordingTelegramBotApi() {
            @Override
            public void sendMessage(String baseUrl, long chatId, String text) {
                throw new RuntimeException("POST " + baseUrl + "/sendMessage failed");
            }
        };
        TelegramChannelSender sender = senderWith(api,
                "{\"enabled\": true, \"botToken\": \"secret-token\", \"allowAllUsers\": true}");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> sender.send("42", "x"));
        assertFalse(e.getMessage().contains("secret-token"), "the bot token must be redacted");
        assertTrue(e.getMessage().contains("/bot<redacted>"));
    }
}
