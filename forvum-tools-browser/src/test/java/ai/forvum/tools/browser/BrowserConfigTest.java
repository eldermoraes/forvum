package ai.forvum.tools.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Unit tests for the on-demand {@link BrowserConfig} reader (mirrors {@code QdrantConfigTest}): the
 * absent-file inert default, parsing, defaults for omitted fields, and the malformed-file failure — driven
 * with an explicit config path (no CDI, no {@code ~/.forvum/}).
 */
class BrowserConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void anAbsentFileIsInertAndDisabled() {
        BrowserConfig config = new BrowserConfig(Path.of("/nonexistent/tools/browser.json"));
        BrowserConfig.Spec spec = config.read();

        assertFalse(spec.enabled(), "no tools/browser.json → the tool is inert (disabled)");
        assertEquals(BrowserConfig.DEFAULT_DEBUG_URL, spec.debugUrl());
        assertEquals(BrowserConfig.DEFAULT_CONNECT_TIMEOUT_MS, spec.connectTimeoutMs());
        assertEquals(BrowserConfig.DEFAULT_COMMAND_TIMEOUT_MS, spec.commandTimeoutMs());
    }

    @Test
    void parsesAFullyPopulatedConfig() {
        BrowserConfig.Spec spec = BrowserConfig.parse(MAPPER.valueToTree(java.util.Map.of(
                "enabled", true,
                "debugUrl", "http://127.0.0.1:9333",
                "connectTimeoutMs", 2000,
                "navigateTimeoutMs", 30000)));

        assertTrue(spec.enabled());
        assertEquals("http://127.0.0.1:9333", spec.debugUrl());
        assertEquals(2000, spec.connectTimeoutMs());
        assertEquals(30000, spec.commandTimeoutMs());
    }

    @Test
    void enabledTrueWithDefaultsForOmittedFields() throws Exception {
        BrowserConfig.Spec spec = BrowserConfig.parse(MAPPER.readTree("{\"enabled\":true}"));

        assertTrue(spec.enabled());
        assertEquals(BrowserConfig.DEFAULT_DEBUG_URL, spec.debugUrl(), "omitted debugUrl falls back to default");
        assertEquals(BrowserConfig.DEFAULT_CONNECT_TIMEOUT_MS, spec.connectTimeoutMs());
        assertEquals(BrowserConfig.DEFAULT_COMMAND_TIMEOUT_MS, spec.commandTimeoutMs());
    }

    @Test
    void enabledDefaultsToFalseWhenAbsentFromThePresentFile() throws Exception {
        // A file that exists but omits "enabled" is treated as disabled (opt-in like the secret-redaction
        // and device-pairing guards): an operator must explicitly enable browser automation.
        BrowserConfig.Spec spec = BrowserConfig.parse(MAPPER.readTree("{\"debugUrl\":\"http://localhost:9222\"}"));
        assertFalse(spec.enabled(), "a present file without \"enabled\":true stays disabled");
    }

    @Test
    void blankDebugUrlAndNonPositiveTimeoutsFallBackToDefaults() throws Exception {
        BrowserConfig.Spec spec = BrowserConfig.parse(MAPPER.readTree(
                "{\"enabled\":true,\"debugUrl\":\"   \",\"connectTimeoutMs\":0,\"navigateTimeoutMs\":-5}"));

        assertEquals(BrowserConfig.DEFAULT_DEBUG_URL, spec.debugUrl());
        assertEquals(BrowserConfig.DEFAULT_CONNECT_TIMEOUT_MS, spec.connectTimeoutMs());
        assertEquals(BrowserConfig.DEFAULT_COMMAND_TIMEOUT_MS, spec.commandTimeoutMs());
    }

    @Test
    void readsAConfigFileFromDisk(@TempDir Path home) throws IOException {
        Path tools = Files.createDirectories(home.resolve("tools"));
        Files.writeString(tools.resolve("browser.json"),
                "{\"enabled\":true,\"debugUrl\":\"http://localhost:9444\"}");

        BrowserConfig.Spec spec = new BrowserConfig(tools.resolve("browser.json")).read();

        assertTrue(spec.enabled());
        assertEquals("http://localhost:9444", spec.debugUrl());
    }

    @Test
    void aMalformedFileThrowsSoTheOperatorSeesTheMisconfiguration(@TempDir Path home) throws IOException {
        Path tools = Files.createDirectories(home.resolve("tools"));
        Files.writeString(tools.resolve("browser.json"), "{ not valid json");

        BrowserConfig config = new BrowserConfig(tools.resolve("browser.json"));
        assertThrows(UncheckedIOException.class, config::read);
    }

    @Test
    void resolveHomeUsesConfiguredHomeWhenPresentElseUserHomeDotForvum() {
        Path configured = BrowserConfig.resolveHome(Optional.of("/srv/forvum"), "/home/u");
        assertTrue(configured.toString().contains("forvum"));

        Path fallback = BrowserConfig.resolveHome(Optional.empty(), "/home/u");
        assertEquals(Path.of("/home/u/.forvum").toAbsolutePath().normalize(), fallback);

        Path blank = BrowserConfig.resolveHome(Optional.of("   "), "/home/u");
        assertEquals(Path.of("/home/u/.forvum").toAbsolutePath().normalize(), blank,
                "a blank forvum.home falls back to <user.home>/.forvum");
    }
}
