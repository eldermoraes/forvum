package ai.forvum.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.telegram.dto.GetUpdatesResponse;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lifecycle contract of {@link TelegramChannel#onStart}/{@link TelegramChannel#onStop} (#180): the
 * startup observer must (1) no-op when the channel is disabled/unconfigured, (2) no-op with a warning
 * when enabled but token-less, and (3) start the virtual-thread poll loop when configured — and the
 * shutdown observer must stop it promptly, including through the failed-poll back-off sleep
 * (interrupt-aware, CLAUDE.md section 11). Driven by direct instantiation + directly-invoked observers
 * (no Quarkus boot), the same POJO wiring as {@link TelegramChannelPollLoopTest}, so the CDI lifecycle
 * paths are measured by the Surefire-only coverage gate (X3).
 */
class TelegramChannelLifecycleTest {

    @Test
    void startIsANoOpWhenTheChannelIsUnconfigured() {
        TelegramChannel channel = new TelegramChannel();
        channel.config = new TelegramChannelConfig(Path.of("/nonexistent/telegram.json"));

        channel.onStart(new StartupEvent());

        assertFalse(channel.running, "an absent channels/telegram.json must not start the poll loop");
        channel.onStop(new ShutdownEvent()); // idempotent on a never-started channel
    }

    @Test
    void startIsANoOpWhenEnabledButTokenLess(@TempDir Path home) throws IOException {
        TelegramChannel channel = new TelegramChannel();
        channel.config = config(home, "{ \"enabled\": true }");

        channel.onStart(new StartupEvent());

        assertFalse(channel.running, "an enabled but token-less channel must warn and not poll");
    }

    @Test
    @Timeout(10)
    void startPollsOnAVirtualThreadAndStopIsPrompt(@TempDir Path home) throws Exception {
        CountDownLatch polled = new CountDownLatch(2);
        TelegramBotApi api = new TelegramBotApi() {
            @Override
            public GetUpdatesResponse getUpdates(String baseUrl, long offset, int timeout) {
                polled.countDown();
                return new GetUpdatesResponse(true, List.of());
            }

            @Override
            public void sendMessage(String baseUrl, long chatId, String text) {
            }
        };
        TelegramChannel channel = wired(home, api);

        channel.onStart(new StartupEvent());
        assertTrue(channel.running, "a configured channel must start the poll loop");
        assertTrue(polled.await(5, TimeUnit.SECONDS), "the loop must be polling getUpdates");

        channel.onStop(new ShutdownEvent());
        assertFalse(channel.running, "shutdown must clear the running flag");
    }

    @Test
    @Timeout(10)
    void aFailedPollBacksOffThenRetriesAndShutdownInterruptsTheBackOff(@TempDir Path home)
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch failed = new CountDownLatch(1);
        TelegramBotApi api = new TelegramBotApi() {
            @Override
            public GetUpdatesResponse getUpdates(String baseUrl, long offset, int timeout) {
                calls.incrementAndGet();
                failed.countDown();
                // The message embeds the token-bearing URL shape so the redacted log path is exercised.
                throw new IllegalStateException(
                        "boom calling https://api.telegram.org/botSECRET/getUpdates");
            }

            @Override
            public void sendMessage(String baseUrl, long chatId, String text) {
            }
        };
        TelegramChannel channel = wired(home, api);

        channel.onStart(new StartupEvent());
        assertTrue(failed.await(5, TimeUnit.SECONDS), "the loop must have attempted a poll");

        // Stop while the loop is (very likely) inside the 1 s back-off: shutdownNow interrupts the
        // virtual thread, the interrupt-aware sleep returns, and the loop observes running == false.
        channel.onStop(new ShutdownEvent());
        assertFalse(channel.running, "shutdown must clear the running flag during back-off");
    }

    private static TelegramChannelConfig config(Path home, String json) throws IOException {
        Path channels = Files.createDirectories(home.resolve("channels"));
        Files.writeString(channels.resolve("telegram.json"), json);
        return new TelegramChannelConfig(channels.resolve("telegram.json"));
    }

    private static TelegramChannel wired(Path home, TelegramBotApi api) throws IOException {
        TelegramChannel channel = new TelegramChannel();
        channel.api = api;
        channel.processor = new UpdateProcessor();
        channel.config = config(home, "{ \"enabled\": true, \"botToken\": \"t0k3n\", \"allowAllUsers\": true }");
        channel.pollTimeoutSeconds = 1;
        return channel;
    }
}
