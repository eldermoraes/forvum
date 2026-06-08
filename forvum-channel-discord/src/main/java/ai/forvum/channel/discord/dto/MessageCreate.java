package ai.forvum.channel.discord.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code d} payload of a {@code MESSAGE_CREATE} dispatch ({@code op 0}, {@code t == "MESSAGE_CREATE"})
 * — a message posted in a channel the bot can see. Forvum consumes:
 *
 * <ul>
 *   <li>{@code author} — the sending {@link Author}; {@code author.bot} distinguishes humans from bots
 *       (including this bot's own echoes, which must be ignored), and {@code author.id} is matched
 *       against {@code allowedUserIds}.</li>
 *   <li>{@code channel_id} — the channel the reply is posted back to via the REST API.</li>
 *   <li>{@code content} — the message text driving the turn (empty when the bot lacks the privileged
 *       MESSAGE_CONTENT intent — such a message drives no turn).</li>
 * </ul>
 *
 * Discord snowflake ids are 64-bit but serialized as JSON strings (to survive JS number precision), so
 * {@code author.id} and {@code channelId} are {@link String}. A real {@code RegisterForReflection}
 * (Quarkus-bearing Layer-3 module) plus {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageCreate(Author author,
                            @JsonProperty("channel_id") String channelId,
                            String content) {

    /**
     * The {@code author} of a {@link MessageCreate} (Discord {@code User} object). {@code bot} is true for
     * bot/webhook authors (Forvum ignores those, including its own replies). {@code id} is the snowflake
     * user id matched against {@code allowedUserIds}. A real {@code RegisterForReflection} plus
     * {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Author(String id, boolean bot) {
    }
}
