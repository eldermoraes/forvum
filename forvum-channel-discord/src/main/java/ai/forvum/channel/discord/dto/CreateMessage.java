package ai.forvum.channel.discord.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The request body for the Discord REST {@code POST /channels/{channelId}/messages} endpoint: a JSON
 * object {@code { "content": "<text>" }}. Forvum sends only the message content (no embeds, no
 * components) in v0.1. A real {@code RegisterForReflection} (Quarkus-bearing Layer-3 module).
 */
@RegisterForReflection
public record CreateMessage(String content) {
}
