package ai.forvum.channel.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The Slack channel's discovery marker reports the stable extension id {@code slack}, matching its
 * {@code META-INF/forvum/plugin.json}. A plain POJO test — the marker carries no Quarkus wiring.
 */
class SlackChannelProviderTest {

    @Test
    void extensionIdIsSlack() {
        assertEquals("slack", new SlackChannelProvider().extensionId());
    }
}
