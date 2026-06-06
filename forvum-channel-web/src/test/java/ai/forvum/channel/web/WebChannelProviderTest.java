package ai.forvum.channel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The Web channel's discovery marker reports the stable extension id {@code web}, matching its
 * {@code META-INF/forvum/plugin.json}. A plain POJO test — the marker carries no Quarkus wiring.
 */
class WebChannelProviderTest {

    @Test
    void extensionIdIsWeb() {
        assertEquals("web", new WebChannelProvider().extensionId());
    }
}
