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

    @Test
    void aDisabledChannelNeverServes() {
        assertFalse(ChannelLauncher.serves("web", json("{ \"enabled\": false }")));
    }

    @Test
    void anEnabledNonTokenGatedChannelServes() {
        assertTrue(ChannelLauncher.serves("web", json("{}")));
    }

    @Test
    void anEnabledTokenGatedChannelServesOnlyWithANonBlankToken() {
        assertFalse(ChannelLauncher.serves("telegram", json("{}")));
        assertFalse(ChannelLauncher.serves("telegram", json("{ \"botToken\": \"   \" }")));
        assertTrue(ChannelLauncher.serves("telegram", json("{ \"botToken\": \"123:abc\" }")));
    }

    @Test
    void hasNonBlankRequiresAPresentNonBlankValueForTheGivenKey() {
        assertFalse(ChannelLauncher.hasNonBlank(json("{}"), "appToken"));
        assertFalse(ChannelLauncher.hasNonBlank(json("{ \"appToken\": \"\" }"), "appToken"));
        assertFalse(ChannelLauncher.hasNonBlank(json("{ \"appToken\": \"  \" }"), "appToken"));
        assertTrue(ChannelLauncher.hasNonBlank(json("{ \"appToken\": \"xapp-1\" }"), "appToken"));
    }

    @Test
    void everyRequiredKeyMustBeNonBlankForACredentialGatedChannelToServe() {
        // The gate is allMatch over each channel's declared key set; pinned per entry here, and
        // exercised at n>1 by the slack entry (botToken+appToken) below.
        for (var entry : ChannelLauncher.REQUIRED_SERVE_KEYS.entrySet()) {
            assertFalse(ChannelLauncher.serves(entry.getKey(), json("{}")),
                    entry.getKey() + " must not serve with no credentials");
        }
    }

    @Test
    void aMultiKeyChannelServesOnlyWithEveryRequiredKeyNonBlank() {
        // The real n>1 allMatch case: Slack requires BOTH botToken (xoxb-, replies) and appToken
        // (xapp-, opens the socket). Any missing or blank key — in either position — must keep the
        // binary in command mode (an enabled config missing one credential would otherwise hang in
        // server mode serving nothing, the M17 trap).
        assertFalse(ChannelLauncher.serves("slack", json("{}")));
        assertFalse(ChannelLauncher.serves("slack", json("{ \"botToken\": \"xoxb-1\" }")),
                "botToken alone (appToken missing) must not serve");
        assertFalse(ChannelLauncher.serves("slack", json("{ \"appToken\": \"xapp-1\" }")),
                "appToken alone (botToken missing) must not serve");
        assertFalse(ChannelLauncher.serves("slack",
                        json("{ \"botToken\": \"xoxb-1\", \"appToken\": \"   \" }")),
                "a blank appToken counts as missing");
        assertFalse(ChannelLauncher.serves("slack",
                        json("{ \"botToken\": \"\", \"appToken\": \"xapp-1\" }")),
                "a blank botToken counts as missing");
        assertTrue(ChannelLauncher.serves("slack",
                json("{ \"botToken\": \"xoxb-1\", \"appToken\": \"xapp-1\" }")));
        assertFalse(ChannelLauncher.serves("slack",
                        json("{ \"enabled\": false, \"botToken\": \"xoxb-1\", \"appToken\": \"xapp-1\" }")),
                "a disabled channel never serves even fully credentialed");
    }
}
