package ai.forvum.channel.telegram;

import ai.forvum.channel.telegram.dto.GetUpdatesResponse;

import io.quarkus.rest.client.reactive.Url;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.QueryParam;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Blocking REST client for the Telegram Bot API (ULTRAPLAN §5.5 / Risk #12). It is a plain blocking
 * client whose methods return typed values directly — NOT a Mutiny {@code Uni}/{@code Multi} return type
 * (reactive where a virtual thread suffices is a PR-reject, CLAUDE.md §3.8). The single caller,
 * {@link TelegramChannel}, runs each call inside a long-poll worker on a virtual thread, where the
 * REST client blocks the virtual thread without pinning the carrier thread.
 *
 * <p>The Telegram Bot API base URL embeds the bot token ({@code https://api.telegram.org/bot<TOKEN>}),
 * which is a per-deployment secret read from {@code channels/telegram.json} at runtime — so it cannot be
 * a compile-time constant or a static config value. Each method takes the resolved base URL as a
 * per-invocation {@code @Url} override ({@code io.quarkus.rest.client.reactive.Url}); the mandatory
 * static {@code quarkus.rest-client."telegram-bot-api".url} is a placeholder the {@code @Url} replaces.
 * {@code read-timeout} must exceed the long-poll {@code timeout} (configured in
 * {@code application.properties}) so the long poll is never cut short by the client.
 */
@RegisterRestClient(configKey = "telegram-bot-api")
public interface TelegramBotApi {

    /**
     * Long-poll for new updates ({@code getUpdates}). {@code offset} acknowledges all updates with a
     * lower id and requests only newer ones; {@code timeout} (seconds) holds the connection open until
     * an update arrives or the timeout elapses (0 = short poll).
     *
     * @param baseUrl per-invocation base URL embedding the bot token
     */
    @GET
    @jakarta.ws.rs.Path("/getUpdates")
    GetUpdatesResponse getUpdates(@Url String baseUrl,
                                  @QueryParam("offset") long offset,
                                  @QueryParam("timeout") int timeout);

    /**
     * Send a text message to {@code chatId} ({@code sendMessage}). The response envelope is ignored — a
     * failed send is surfaced by the thrown exception, logged by the caller.
     *
     * @param baseUrl per-invocation base URL embedding the bot token
     */
    @POST
    @jakarta.ws.rs.Path("/sendMessage")
    void sendMessage(@Url String baseUrl,
                     @QueryParam("chat_id") long chatId,
                     @QueryParam("text") String text);
}
