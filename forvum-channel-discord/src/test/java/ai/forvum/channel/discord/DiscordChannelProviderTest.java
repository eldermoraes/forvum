package ai.forvum.channel.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The Discord channel's discovery marker reports the stable extension id {@code discord}, matching its
 * {@code META-INF/forvum/plugin.json}. A plain POJO test — the marker carries no Quarkus wiring.
 */
class DiscordChannelProviderTest {

    @Test
    void extensionIdIsDiscord() {
        assertEquals("discord", new DiscordChannelProvider().extensionId());
    }
}
