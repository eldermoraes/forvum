package ai.forvum.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.telegram.RecordingTelegramBotApi.Sent;
import ai.forvum.channel.telegram.dto.GetUpdatesResponse;
import ai.forvum.channel.telegram.dto.TelegramChat;
import ai.forvum.channel.telegram.dto.TelegramMessage;
import ai.forvum.channel.telegram.dto.TelegramUpdate;
import ai.forvum.channel.telegram.dto.TelegramUser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The long-poll cycle ({@link TelegramChannel#pollLoop}): {@code getUpdates} →
 * {@code UpdateProcessor.process} (drive a turn) → {@code sendMessage} → advance the offset, then exit.
 *
 * <p>A plain POJO unit test (no Quarkus boot): {@code TelegramChannel}, {@code UpdateProcessor}, and
 * {@code FakeTurnDriver} are constructed directly and wired by hand, so the loop's {@code running} flag
 * and collaborators are real instance fields (not CDI client-proxy fields — direct field access on an
 * {@code @ApplicationScoped} proxy reads the proxy's own field, not the contextual instance's). The
 * {@link RecordingTelegramBotApi} stands in for the REST client, exercising getUpdates → dispatch →
 * sendMessage with no live Telegram service.
 */
class TelegramChannelPollLoopTest {

    private static TelegramUpdate textUpdate(long updateId, long userId, long chatId, String text) {
        return new TelegramUpdate(updateId,
                new TelegramMessage(new TelegramUser(userId), new TelegramChat(chatId), text));
    }

    /** Wire a {@code TelegramChannel} with a fake driver + a config pointing at an absent file. */
    private static TelegramChannel wiredChannel(FakeTurnDriver driver) {
        UpdateProcessor processor = new UpdateProcessor();
        processor.turns = driver;

        TelegramChannel channel = new TelegramChannel();
        channel.processor = processor;
        channel.config = new TelegramChannelConfig(Path.of("/nonexistent/telegram.json"));
        channel.pollTimeoutSeconds = 1;
        return channel;
    }

    @Test
    void aPollCycleDrivesTheTurnAndSendsTheReplyThenAdvancesOffsetAndStops() {
        FakeTurnDriver driver = new FakeTurnDriver();
        TelegramChannel channel = wiredChannel(driver);

        // A stopping API: scripts one batch with a real update, then stops the loop on the next poll so
        // the while(running) terminates deterministically.
        RecordingTelegramBotApi api = new RecordingTelegramBotApi() {
            @Override
            public GetUpdatesResponse getUpdates(String baseUrl, long offset, int timeout) {
                GetUpdatesResponse next = scriptedResponses.poll();
                if (next != null) {
                    return next;
                }
                channel.running = false; // exit after the scripted batch is drained
                return new GetUpdatesResponse(true, List.of());
            }
        };
        api.scriptedResponses.add(new GetUpdatesResponse(true,
                List.of(textUpdate(100L, 42L, 7L, "ping"))));

        // The default config (absent file) yields an empty allow-list => any user allowed, so the turn
        // runs; that the loop ran is the assertion.
        channel.running = true;
        channel.pollLoop(api, "http://base");

        assertEquals(1, driver.dispatched().size(), "the poll cycle drove exactly one turn");
        assertEquals("ping", driver.dispatched().get(0).content());

        List<Sent> sent = api.sent;
        assertEquals(1, sent.size(), "the rendered reply was sent back");
        assertEquals(7L, sent.get(0).chatId());
        assertEquals("echo:ping", sent.get(0).text());
    }

    @Test
    void disabledChannelLeavesTheLoopUnstartedWithoutThrowing() {
        FakeTurnDriver driver = new FakeTurnDriver();
        TelegramChannel channel = wiredChannel(driver);

        // onStart with the absent-file config (Spec.empty() => enabled=false) hits the DISABLED branch:
        // info + no-op, never throw and never start a poller — the CI native no-config boot contract.
        channel.onStart(null);

        assertFalse(channel.running, "a disabled channel never starts the poll loop");
        assertTrue(driver.dispatched().isEmpty(), "no turn is driven when the channel is disabled");
        // onStop must be safe even though no poller was started.
        channel.onStop(null);
    }

    @Test
    void enabledButTokenlessChannelLeavesTheLoopUnstartedWithoutThrowing(@TempDir Path home)
            throws IOException {
        FakeTurnDriver driver = new FakeTurnDriver();
        UpdateProcessor processor = new UpdateProcessor();
        processor.turns = driver;

        // An ENABLED telegram.json with NO botToken => the "no token" branch: WARN + no-op. This is the
        // branch the previous (renamed) test never reached, since an absent file is also disabled.
        Path channels = Files.createDirectories(home.resolve("channels"));
        Files.writeString(channels.resolve("telegram.json"), "{ \"enabled\": true }");

        TelegramChannel channel = new TelegramChannel();
        channel.processor = processor;
        channel.config = new TelegramChannelConfig(channels.resolve("telegram.json"));
        channel.pollTimeoutSeconds = 1;

        channel.onStart(null);

        assertFalse(channel.running, "an enabled but token-less channel never starts the poll loop");
        assertTrue(driver.dispatched().isEmpty(), "no turn is driven when the channel has no token");
        channel.onStop(null);
    }
}
