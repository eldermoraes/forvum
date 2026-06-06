package ai.forvum.channel.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The Telegram Bot API envelope for {@code getUpdates}: {@code { "ok": true, "result": [Update...] }}.
 * Forvum reads {@code result} (the batch of {@link TelegramUpdate}); {@code result} may be {@code null}
 * on an error envelope, so callers must null-guard. A real {@code RegisterForReflection}
 * (Quarkus-bearing Layer-3 module) plus {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record GetUpdatesResponse(boolean ok, List<TelegramUpdate> result) {
}
