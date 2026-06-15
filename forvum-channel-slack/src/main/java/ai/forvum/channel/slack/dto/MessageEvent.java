package ai.forvum.channel.slack.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code payload.event} of an {@code events_api} Socket Mode frame whose event type is
 * {@code "message"} — a message posted in a conversation the bot is in. Forvum consumes:
 *
 * <ul>
 *   <li>{@code subtype} — non-null for anything that is NOT a plain user message (edits
 *       {@code message_changed}, deletions, joins, file shares, bot-styled messages); such events drive
 *       no turn.</li>
 *   <li>{@code user} — the sending user id (e.g. {@code U0123ABC}), matched against
 *       {@code allowedUserIds}; absent on some system/bot messages (those drive no turn).</li>
 *   <li>{@code bot_id} — present when a bot (including this bot's own replies) authored the message;
 *       such messages are ignored so the channel never converses with itself.</li>
 *   <li>{@code channel} — the conversation id the reply is posted back to via {@code chat.postMessage}.</li>
 *   <li>{@code text} — the message text driving the turn.</li>
 *   <li>{@code thread_ts} — present when the message lives in a thread. v0.1 replies in-channel plain
 *       (threaded replies are a documented follow-up); captured so that follow-up is a non-breaking
 *       change.</li>
 * </ul>
 *
 * Slack user/channel ids are opaque strings (not numeric), so they stay {@link String}. A real
 * {@code RegisterForReflection} (Quarkus-bearing Layer-3 module) plus
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageEvent(String type,
                           String subtype,
                           String user,
                           @JsonProperty("bot_id") String botId,
                           String channel,
                           String text,
                           @JsonProperty("thread_ts") String threadTs) {
}
