package ai.forvum.channel.discord.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code d} payload of the {@code op 2} IDENTIFY frame the client sends after HELLO to authenticate
 * and open a fresh session. Forvum sends the minimal fields:
 *
 * <ul>
 *   <li>{@code token} — the bot token (a per-deployment secret from {@code channels/discord.json}); it
 *       must never reach the logs (the IDENTIFY frame is serialized only to the socket, never logged).</li>
 *   <li>{@code intents} — the bitmask of gateway intents. Forvum requests {@code GUILD_MESSAGES (1<<9)}
 *       {@code | DIRECT_MESSAGES (1<<12)} {@code | MESSAGE_CONTENT (1<<15)} so {@code MESSAGE_CREATE}
 *       events carry the message {@code content} (a privileged intent that must be enabled on the bot's
 *       application).</li>
 *   <li>{@code properties} — required connection properties ({@code os}/{@code browser}/{@code device}).</li>
 * </ul>
 *
 * A real {@code RegisterForReflection} (Quarkus-bearing Layer-3 module).
 */
@RegisterForReflection
public record Identify(String token, int intents, @JsonProperty("properties") Properties properties) {

    /**
     * Build an IDENTIFY payload for {@code token} with Forvum's fixed intent set and connection
     * properties.
     */
    public static Identify of(String token, int intents) {
        return new Identify(token, intents, Properties.forvum());
    }

    /**
     * The required {@code properties} object of an IDENTIFY payload. Cosmetic connection metadata; the
     * values are not validated by the gateway. A real {@code RegisterForReflection}.
     */
    @RegisterForReflection
    public record Properties(String os,
                             @JsonProperty("browser") String browser,
                             @JsonProperty("device") String device) {

        static Properties forvum() {
            return new Properties(System.getProperty("os.name", "linux"), "forvum", "forvum");
        }
    }
}
