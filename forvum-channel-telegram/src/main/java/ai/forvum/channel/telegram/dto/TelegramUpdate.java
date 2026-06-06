package ai.forvum.channel.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One entry in the Telegram Bot API {@code getUpdates} result (the {@code Update} object). {@code
 * update_id} drives long-poll offset advancement (the next poll requests {@code offset =
 * maxSeenUpdateId + 1} to acknowledge processed updates); {@code message} is absent for update kinds
 * Forvum does not handle (edited messages, callback queries, ...). A real {@code RegisterForReflection}
 * (Quarkus-bearing Layer-3 module) plus {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramUpdate(@JsonProperty("update_id") long updateId, TelegramMessage message) {
}
