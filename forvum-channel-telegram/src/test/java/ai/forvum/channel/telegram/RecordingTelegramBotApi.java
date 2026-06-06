package ai.forvum.channel.telegram;

import ai.forvum.channel.telegram.dto.GetUpdatesResponse;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test double for {@link TelegramBotApi}: {@code getUpdates} replays scripted responses (then returns
 * an empty batch), and {@code sendMessage} records every outbound {@code (chatId, text)}. Used directly
 * (not as a CDI bean) so {@code UpdateProcessor.process} and {@code TelegramChannel.pollLoop} can be
 * driven without a live Telegram service or a mocked HTTP endpoint.
 */
class RecordingTelegramBotApi implements TelegramBotApi {

    final Deque<GetUpdatesResponse> scriptedResponses = new ArrayDeque<>();
    final CopyOnWriteArrayList<Sent> sent = new CopyOnWriteArrayList<>();

    record Sent(long chatId, String text) {
    }

    @Override
    public GetUpdatesResponse getUpdates(String baseUrl, long offset, int timeout) {
        GetUpdatesResponse next = scriptedResponses.poll();
        return next != null ? next : new GetUpdatesResponse(true, List.of());
    }

    @Override
    public void sendMessage(String baseUrl, long chatId, String text) {
        sent.add(new Sent(chatId, text));
    }
}
