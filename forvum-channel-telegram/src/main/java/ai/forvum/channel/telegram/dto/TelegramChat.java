package ai.forvum.channel.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code chat} field of a Telegram {@link TelegramMessage} (Telegram Bot API {@code Chat} object).
 * Only {@code id} is consumed: it is the {@code chat_id} the assistant reply is sent back to via
 * {@code sendMessage}. A real {@code RegisterForReflection} (Quarkus-bearing Layer-3 module) plus
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramChat(long id) {
}
