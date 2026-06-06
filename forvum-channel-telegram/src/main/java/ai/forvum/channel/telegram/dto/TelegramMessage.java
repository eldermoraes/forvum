package ai.forvum.channel.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code message} field of a Telegram {@link TelegramUpdate} (Telegram Bot API {@code Message}
 * object). Forvum consumes the sending user ({@code from}, for {@code allowedUserIds}), the chat
 * ({@code chat}, for the reply target), and the text ({@code text}). {@code from} and {@code text} may
 * be absent for non-text or service messages, so both are nullable. A real {@code RegisterForReflection}
 * (Quarkus-bearing Layer-3 module) plus {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramMessage(TelegramUser from, TelegramChat chat, String text) {
}
