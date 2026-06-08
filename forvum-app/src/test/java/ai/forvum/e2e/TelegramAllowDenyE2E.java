package ai.forvum.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.telegram.TelegramBotApi;
import ai.forvum.channel.telegram.TelegramChannelConfig.Spec;
import ai.forvum.channel.telegram.UpdateProcessor;
import ai.forvum.channel.telegram.dto.GetUpdatesResponse;
import ai.forvum.channel.telegram.dto.TelegramChat;
import ai.forvum.channel.telegram.dto.TelegramMessage;
import ai.forvum.channel.telegram.dto.TelegramUpdate;
import ai.forvum.channel.telegram.dto.TelegramUser;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * E2E scenario 7 (ULTRAPLAN §7.4 / X6): the Telegram channel's allowed/denied-user enforcement (M17). It
 * drives the real {@code UpdateProcessor} bean — which, in the assembled app, injects the engine's
 * {@code TurnService} as its {@code ChannelTurnDriver} (Resolution B) — against an in-process recording
 * {@code TelegramBotApi}, end-to-end through the in-process fake model. No real Telegram, no real LLM (the
 * suite excludes inference per the perf-gate convention).
 *
 * <p>Two halves of the M17 Verify ("{@code allowedUserIds} refuses other users with a friendly message"):
 * <ul>
 *   <li><strong>Allowed user:</strong> with {@code allowedUserIds = {42}}, user 42 drives a real turn; the
 *       fake model's reply ({@code "pong"}) is sent back to the user's chat.</li>
 *   <li><strong>Denied user:</strong> user 999 (not in the allow-list) gets the friendly refusal sent to
 *       their chat and NO turn runs (the refusal never reaches the agent runtime).</li>
 * </ul>
 *
 * <p>Asserting via the recorded {@code sendMessage} calls is the observable side-effect (OTel spans do not
 * exist in v0.1, per X6's span-less guidance): the allowed user's chat receives the model reply; the
 * denied user's chat receives exactly the refusal text and nothing else.
 */
@QuarkusTest
@TestProfile(TelegramAllowDenyE2E.FakeBackedHomeProfile.class)
class TelegramAllowDenyE2E {

    @Inject
    UpdateProcessor processor;

    @Test
    void anAllowedUserConversesAndADeniedUserIsRefusedWithNoTurn() {
        Spec spec = new Spec(true, Optional.of("token"), Set.of(42L)); // only user 42 allowed
        RecordingBotApi api = new RecordingBotApi();

        // Allowed user 42 -> a real turn through the fake model, reply sent to chat 7.
        processor.process(textUpdate(1L, 42L, 7L, "hello"), spec, api, "http://base");
        // Denied user 999 -> a friendly refusal sent to chat 8, no turn.
        processor.process(textUpdate(2L, 999L, 8L, "let me in"), spec, api, "http://base");

        List<Sent> chat7 = api.sentTo(7L);
        assertEquals(1, chat7.size(), "the allowed user must receive exactly the model reply");
        assertEquals("pong", chat7.get(0).text(),
                "the engine drove the turn end-to-end through the in-process fake model");

        List<Sent> chat8 = api.sentTo(8L);
        assertEquals(1, chat8.size(), "the denied user must receive exactly the refusal");
        assertTrue(chat8.get(0).text().toLowerCase().contains("not authorized"),
                "the refusal must be a friendly authorization message (REFUSAL_MESSAGE is package-private)");
        assertFalse(chat8.get(0).text().equals("pong"), "a denied user must NOT receive a model reply");
    }

    private static TelegramUpdate textUpdate(long updateId, long userId, long chatId, String text) {
        return new TelegramUpdate(updateId,
                new TelegramMessage(new TelegramUser(userId), new TelegramChat(chatId), text));
    }

    /** One recorded outbound {@code sendMessage}. */
    private record Sent(long chatId, String text) { }

    /**
     * In-process recording {@link TelegramBotApi}: captures every {@code sendMessage} so the test can
     * assert what each chat received. {@code getUpdates} is never exercised here (the test feeds updates
     * to {@code UpdateProcessor} directly), so it returns nothing.
     */
    private static final class RecordingBotApi implements TelegramBotApi {

        private final List<Sent> sent = new ArrayList<>();

        @Override
        public GetUpdatesResponse getUpdates(String baseUrl, long offset, int timeout) {
            return new GetUpdatesResponse(true, List.of());
        }

        @Override
        public void sendMessage(String baseUrl, long chatId, String text) {
            sent.add(new Sent(chatId, text));
        }

        List<Sent> sentTo(long chatId) {
            return sent.stream().filter(s -> s.chatId() == chatId).toList();
        }
    }

    /** Seeds {@code main} pinned to the in-process {@code fake} provider so the allowed user's turn needs no LLM. */
    public static class FakeBackedHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-telegram-e2e-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", HOME.toString());
        }
    }
}
