package ai.forvum.channel.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The Voice channel's discovery marker reports the stable extension id {@code voice}, matching its
 * {@code META-INF/forvum/plugin.json}. A plain POJO test — the marker carries no Quarkus wiring.
 */
class VoiceChannelProviderTest {

    @Test
    void extensionIdIsVoice() {
        assertEquals("voice", new VoiceChannelProvider().extensionId());
    }
}
