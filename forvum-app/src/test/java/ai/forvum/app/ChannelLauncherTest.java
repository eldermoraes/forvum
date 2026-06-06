package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * The channel-enablement rule that drives command-mode-vs-server-mode dispatch: a channel is enabled
 * unless its config explicitly sets {@code "enabled": false}, and an absent config is disabled. A plain
 * unit test over {@link ChannelLauncher#isEnabled(JsonNode)} — no Quarkus boot.
 */
class ChannelLauncherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(raw, e);
        }
    }

    @Test
    void absentConfigIsDisabled() {
        assertFalse(ChannelLauncher.isEnabled(null));
    }

    @Test
    void configWithoutEnabledFieldDefaultsToEnabled() {
        assertTrue(ChannelLauncher.isEnabled(json("{}")));
    }

    @Test
    void explicitTrueIsEnabled() {
        assertTrue(ChannelLauncher.isEnabled(json("{ \"enabled\": true }")));
    }

    @Test
    void explicitFalseIsDisabled() {
        assertFalse(ChannelLauncher.isEnabled(json("{ \"enabled\": false }")));
    }
}
