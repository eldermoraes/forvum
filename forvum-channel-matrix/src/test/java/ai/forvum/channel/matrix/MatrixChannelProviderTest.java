package ai.forvum.channel.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The Matrix channel's discovery marker reports the stable extension id {@code matrix}, matching its
 * {@code META-INF/forvum/plugin.json}. A plain POJO test — the marker carries no Quarkus wiring.
 */
class MatrixChannelProviderTest {

    @Test
    void extensionIdIsMatrix() {
        assertEquals("matrix", new MatrixChannelProvider().extensionId());
    }
}
