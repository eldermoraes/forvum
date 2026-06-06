package ai.forvum.channel.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The TUI channel's discovery marker reports the stable extension id {@code tui}, matching its
 * {@code META-INF/forvum/plugin.json}. A plain POJO test — the marker carries no Quarkus wiring.
 */
class TuiChannelProviderTest {

    @Test
    void extensionIdIsTui() {
        assertEquals("tui", new TuiChannelProvider().extensionId());
    }
}
