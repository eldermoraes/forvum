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
        // exercised at n>1 by the Slack (botToken+appToken), Matrix (homeserver+accessToken+userId),
        // and Signal (baseUrl+account) cases below.
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

    @Test
    void matrixServesOnlyWithHomeserverAccessTokenAndUserId() {
        // The first multi-key entry: the allMatch gate must require EVERY declared key, so one
        // credential present without the others is NOT serving (an enabled but half-configured
        // matrix.json would otherwise hang the binary in server mode serving nothing — the M17 trap).
        // userId is serve-required too: MatrixChannel.onStart no-ops without it (the self-echo gate),
        // so a creds-but-no-userId config must not count as serving either.
        assertFalse(ChannelLauncher.serves("matrix", json("{}")));
        assertFalse(ChannelLauncher.serves("matrix",
                json("{ \"homeserver\": \"https://m.example.org\" }")));
        assertFalse(ChannelLauncher.serves("matrix", json("{ \"accessToken\": \"syt_abc\" }")));
        assertFalse(ChannelLauncher.serves("matrix",
                json("{ \"homeserver\": \"  \", \"accessToken\": \"syt_abc\", "
                        + "\"userId\": \"@bot:example.org\" }")));
        assertFalse(ChannelLauncher.serves("matrix",
                json("{ \"homeserver\": \"https://m.example.org\", \"accessToken\": \"syt_abc\" }")),
                "creds without the bot's own userId must not serve — onStart would warn + no-op");
        assertFalse(ChannelLauncher.serves("matrix",
                json("{ \"enabled\": false, \"homeserver\": \"https://m.example.org\", "
                        + "\"accessToken\": \"syt_abc\", \"userId\": \"@bot:example.org\" }")));
        assertTrue(ChannelLauncher.serves("matrix",
                json("{ \"homeserver\": \"https://m.example.org\", \"accessToken\": \"syt_abc\", "
                        + "\"userId\": \"@bot:example.org\" }")));
    }

    @Test
    void signalServesOnlyWithBothBaseUrlAndAccount() {
        // Signal declares TWO required keys (baseUrl + account): the allMatch gate must refuse a
        // config carrying only one of them (or a blank one), else an enabled-but-half-configured
        // signal.json would hang the binary in server mode serving nothing (the M17 trap at n>1).
        assertFalse(ChannelLauncher.serves("signal", json("{}")));
        assertFalse(ChannelLauncher.serves("signal",
                json("{ \"baseUrl\": \"http://localhost:8080\" }")), "account missing");
        assertFalse(ChannelLauncher.serves("signal",
                json("{ \"account\": \"+15559990000\" }")), "baseUrl missing");
        assertFalse(ChannelLauncher.serves("signal",
                json("{ \"baseUrl\": \"http://localhost:8080\", \"account\": \"   \" }")),
                "a blank account is not a credential");
        assertTrue(ChannelLauncher.serves("signal",
                json("{ \"baseUrl\": \"http://localhost:8080\", \"account\": \"+15559990000\" }")));
        assertFalse(ChannelLauncher.serves("signal",
                json("{ \"enabled\": false, \"baseUrl\": \"http://localhost:8080\","
                        + " \"account\": \"+15559990000\" }")),
                "a disabled channel never serves, even fully configured");
    }
}
