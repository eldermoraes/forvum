package ai.forvum.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.telegram.RecordingTelegramBotApi.Sent;
import ai.forvum.channel.telegram.TelegramChannelConfig.Spec;
import ai.forvum.channel.telegram.dto.TelegramChat;
import ai.forvum.channel.telegram.dto.TelegramMessage;
import ai.forvum.channel.telegram.dto.TelegramUpdate;
import ai.forvum.channel.telegram.dto.TelegramUser;
import ai.forvum.core.ChannelMessage;

import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code UpdateProcessor} maps a Telegram update to a turn and enforces {@code allowedUserIds}
 * (ULTRAPLAN §5.5 / the M17 Verify). Drives the real {@code UpdateProcessor} bean against the in-module
 * {@link FakeTurnDriver} (the engine's {@code TurnService} is banned by the Layer-3 enforcer) and a
 * {@link RecordingTelegramBotApi}. Boots Quarkus in-JVM; runs under Surefire (headless library, CLAUDE.md
 * §4 exception).
 */
@QuarkusTest
class UpdateProcessorIT {

    @Inject
    UpdateProcessor processor;

    @Inject
    FakeTurnDriver driver;

    private RecordingTelegramBotApi api;

    @BeforeEach
    void resetDriver() {
        driver.reset();
        api = new RecordingTelegramBotApi();
    }

    private static TelegramUpdate textUpdate(long updateId, long userId, long chatId, String text) {
        return new TelegramUpdate(updateId,
                new TelegramMessage(new TelegramUser(userId), new TelegramChat(chatId), text));
    }

    @Test
    void anAllowedUserDrivesATurnAndTheReplyIsSentBack() {
        // empty allow-list => any user allowed
        Spec spec = new Spec(true, Optional.of("token"), Set.of());

        processor.process(textUpdate(1L, 42L, 7L, "hello"), spec, api, "http://base");

        assertEquals(1, driver.dispatched().size(), "an allowed user must drive exactly one turn");
        ChannelMessage dispatched = driver.dispatched().get(0);
        assertEquals("telegram", dispatched.channelId());
        assertEquals("42", dispatched.nativeUserId(), "native user id is the Telegram user id as String");
        assertEquals("hello", dispatched.content());

        List<Sent> sent = api.sent;
        assertEquals(1, sent.size(), "the TokenDelta reply is sent; the terminal Done renders to nothing");
        assertEquals(7L, sent.get(0).chatId());
        assertEquals("echo:hello", sent.get(0).text());
    }

    @Test
    void aDisallowedUserIsRefusedWithAFriendlyMessageAndNoTurnRuns() {
        Spec spec = new Spec(true, Optional.of("token"), Set.of(42L)); // only 42 allowed

        processor.process(textUpdate(2L, 999L, 8L, "let me in"), spec, api, "http://base");

        assertTrue(driver.dispatched().isEmpty(), "a refused user must NOT drive a turn");
        assertEquals(1, api.sent.size(), "the refusal must be sent back to the user's chat");
        assertEquals(8L, api.sent.get(0).chatId());
        assertEquals(UpdateProcessor.REFUSAL_MESSAGE, api.sent.get(0).text());
    }

    @Test
    void anAllowedListedUserDrivesATurn() {
        Spec spec = new Spec(true, Optional.of("token"), Set.of(42L));

        processor.process(textUpdate(3L, 42L, 9L, "hi"), spec, api, "http://base");

        assertEquals(1, driver.dispatched().size());
        assertEquals("echo:hi", api.sent.get(0).text());
    }

    @Test
    void anUpdateWithoutATextMessageIsSkipped() {
        Spec spec = new Spec(true, Optional.of("token"), Set.of());

        processor.process(new TelegramUpdate(4L, null), spec, api, "http://base");

        assertTrue(driver.dispatched().isEmpty(), "a non-message update drives no turn");
        assertTrue(api.sent.isEmpty(), "a non-message update sends nothing");
    }
}
