package ai.forvum.channel.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code from} field of a Telegram {@link TelegramMessage} — the user who sent it (Telegram Bot API
 * {@code User} object). Only {@code id} is consumed: it is the native user id matched against
 * {@code allowedUserIds} and stamped onto the {@link ai.forvum.core.ChannelMessage}. A real
 * {@code io.quarkus.runtime.annotations.RegisterForReflection} (this is a Quarkus-bearing Layer-3
 * module) plus {@code @JsonIgnoreProperties(ignoreUnknown = true)} so the many unconsumed Bot API
 * fields are dropped.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramUser(long id) {
}
