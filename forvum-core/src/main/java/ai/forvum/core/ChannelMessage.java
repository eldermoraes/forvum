package ai.forvum.core;

import java.time.Instant;

/**
 * An inbound message handed from a channel's inbound handler to the engine (ULTRAPLAN section 5.3).
 * {@code nativeUserId} is the channel's raw user id (Telegram user id, web session cookie, OS username)
 * which the engine resolves into an {@code Identity}. Outbound tokens flow back as
 * {@link ai.forvum.core.event.TokenDelta} events, not as {@code ChannelMessage}.
 */
public record ChannelMessage(String channelId, String nativeUserId, String content, Instant timestamp) {
    public ChannelMessage {
        if (channelId == null || channelId.isBlank() || !channelId.strip().equals(channelId)) {
            throw new IllegalStateException(
                "ChannelMessage channelId must be a non-blank token without leading/trailing "
              + "whitespace. Got: '" + channelId + "'.");
        }
        if (nativeUserId == null || nativeUserId.isBlank()) {
            throw new IllegalStateException(
                "ChannelMessage nativeUserId must be non-null and non-blank. Got: '" + nativeUserId
              + "'. The channel inbound handler must supply the native user id.");
        }
        if (content == null) {
            throw new IllegalStateException(
                "ChannelMessage content must be non-null (an empty string is allowed).");
        }
        if (timestamp == null) {
            throw new IllegalStateException(
                "ChannelMessage timestamp must be non-null.");
        }
    }
}
