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
        // The gate is allMatch over the declared set, so a channel declaring several keys serves only
        // with ALL present — pinned per entry here, and exercised at n>1 by the Matrix case below.
        for (var entry : ChannelLauncher.REQUIRED_SERVE_KEYS.entrySet()) {
            assertFalse(ChannelLauncher.serves(entry.getKey(), json("{}")),
                    entry.getKey() + " must not serve with no credentials");
        }
    }

    @Test
    void matrixServesOnlyWithBothHomeserverAndAccessToken() {
        // The first multi-key entry: the allMatch gate must require EVERY declared key, so one
        // credential present without the other is NOT serving (an enabled but half-configured
        // matrix.json would otherwise hang the binary in server mode serving nothing — the M17 trap).
        assertFalse(ChannelLauncher.serves("matrix", json("{}")));
        assertFalse(ChannelLauncher.serves("matrix",
                json("{ \"homeserver\": \"https://m.example.org\" }")));
        assertFalse(ChannelLauncher.serves("matrix", json("{ \"accessToken\": \"syt_abc\" }")));
        assertFalse(ChannelLauncher.serves("matrix",
                json("{ \"homeserver\": \"  \", \"accessToken\": \"syt_abc\" }")));
        assertFalse(ChannelLauncher.serves("matrix",
                json("{ \"enabled\": false, \"homeserver\": \"https://m.example.org\", "
                        + "\"accessToken\": \"syt_abc\" }")));
        assertTrue(ChannelLauncher.serves("matrix",
                json("{ \"homeserver\": \"https://m.example.org\", \"accessToken\": \"syt_abc\" }")));
    }
}
