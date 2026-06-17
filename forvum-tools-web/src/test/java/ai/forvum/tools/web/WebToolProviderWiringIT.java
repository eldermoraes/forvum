package ai.forvum.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.ToolProvider;

import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Verifies the web tool provider WIRES under Quarkus: ArC discovers it as a {@link ToolProvider} bean, the
 * {@code @RestClient BraveSearchApi} and the {@code HttpFetcher} / {@code WebToolConfig} beans inject (the
 * native-relevant CDI + rest-client path). With {@code forvum.home} pinned to an absent path (no
 * {@code tools/web.json}), {@code web.search} returns the not-configured string and issues no call — the
 * inert posture the CI native no-config smoke depends on. Boots Quarkus in-JVM; runs under Surefire
 * (headless library, CLAUDE.md §4 exception).
 */
@QuarkusTest
class WebToolProviderWiringIT {

    @Inject
    ToolProvider provider;   // resolves to the single WebToolProvider bean

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
    void webSearchIsInertWithNoConfig() {
        // The test JVM has forvum.home pinned to a path with no tools/web.json and no Brave key, so
        // web.search must return the not-configured string and make no network call.
        String out = provider.invoke("web.search", java.util.Map.of("query", "anything"));
        assertTrue(out.toLowerCase().contains("not configured"), out);
    }
}
