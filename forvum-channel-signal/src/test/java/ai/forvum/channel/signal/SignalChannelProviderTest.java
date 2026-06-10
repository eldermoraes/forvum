package ai.forvum.channel.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The Signal channel's discovery marker reports the stable extension id {@code signal}, matching its
 * {@code META-INF/forvum/plugin.json}. A plain POJO test — the marker carries no Quarkus wiring.
 */
class SignalChannelProviderTest {

    @Test
    void extensionIdIsSignal() {
        assertEquals("signal", new SignalChannelProvider().extensionId());
    }
}
