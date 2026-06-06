package ai.forvum.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The Telegram channel's discovery marker reports the stable extension id {@code telegram}, matching its
 * {@code META-INF/forvum/plugin.json}. A plain POJO test — the marker carries no Quarkus wiring.
 */
class TelegramChannelProviderTest {

    @Test
    void extensionIdIsTelegram() {
        assertEquals("telegram", new TelegramChannelProvider().extensionId());
    }
}
