package ai.forvum.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.ToolProvider;

import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Verifies the web tool provider WIRES under Quarkus: ArC discovers it as a {@link ToolProvider} bean, the
 * {@code @RestClient BraveSearchApi} and the {@code HttpFetcher} / {@code WebToolConfig} beans inject (the
 * native-relevant CDI + rest-client path). Boots Quarkus in-JVM; runs under Surefire (headless library,
 * CLAUDE.md §4 exception).
 *
 * <p><strong>#192 note:</strong> the keyless DuckDuckGo default flips {@code web.search} from inert to
 * network-active with NO config, so we CANNOT invoke it against the real fetcher without dialing the
 * internet. Instead this seeds {@code tools/web.json} = {@code {"backend":"brave"}} (no key) under the
 * pinned absent home, so the invoke resolves to the config-shaped "needs braveApiKey" message and makes NO
 * network call (also incidentally proving the on-demand hot-read of the backend field). A LIVE keyless
 * search is the module-level {@code @Tag("live")} {@link DuckDuckGoSearchLiveTest}.
 */
@QuarkusTest
class WebToolProviderWiringIT {

    /** Must match {@code forvum.home} in {@code src/test/resources/application.properties}. */
    private static final Path HOME =
            Path.of(System.getProperty("java.io.tmpdir")).resolve("forvum-web-it-home-absent");
    private static final Path WEB_JSON = HOME.resolve("tools").resolve("web.json");

    @Inject
    ToolProvider provider;   // resolves to the single WebToolProvider bean

    @BeforeAll
    static void seedBackendBraveNoKey() throws IOException {
        Files.createDirectories(WEB_JSON.getParent());
        // backend=brave with NO braveApiKey → the config-shaped message path, zero network.
        Files.writeString(WEB_JSON, "{ \"backend\": \"brave\" }");
    }

    @AfterAll
    static void removeSeed() throws IOException {
        Files.deleteIfExists(WEB_JSON);
    }

    @Test
    void beanIsDiscoveredWithTheExpectedExtensionIdAndTools() {
        assertNotNull(provider);
        assertEquals("web", provider.extensionId());
        List<ToolSpec> tools = provider.tools();
        assertEquals(2, tools.size());
        assertTrue(tools.contains(WebFetchTool.SPEC));
        assertTrue(tools.contains(WebSearchTool.SPEC));
    }

    @Test
    void webSearchWithBraveBackendAndNoKeyReturnsConfigMessageAndNoNetwork() {
        // The seeded tools/web.json selects the brave backend but supplies no key, so web.search must
        // return the actionable config message and make NO network call (the CI-safe inert posture).
        String out = provider.invoke("web.search", java.util.Map.of("query", "anything"));
        assertTrue(out.contains("braveApiKey"), out);
        assertTrue(out.contains("tools/web.json"), out);
    }
}
