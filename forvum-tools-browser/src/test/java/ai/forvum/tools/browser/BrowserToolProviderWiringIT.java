package ai.forvum.tools.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.ToolProvider;

import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Verifies the browser tool WIRES under Quarkus: ArC discovers {@link BrowserToolProvider} as a
 * {@link ToolProvider} bean (the engine's ToolRegistry path) and its {@code @ApplicationScoped}
 * collaborators ({@link CdpSession}/{@link BrowserConfig}) inject (the native-relevant CDI + websockets-next
 * path the no-config smoke depends on — the CDP transport is now a {@code BasicWebSocketConnector} dialed
 * from {@link CdpSession}, with NO {@code @WebSocketClient} endpoint, so it cannot collide with the discord
 * gateway endpoint's {@code path = "/"} on the assembled app classpath). With no
 * {@code tools/browser.json} (the test pins {@code forvum.home} to an absent path), {@code invoke} returns
 * the graceful "disabled" error STRING — never throws and never opens a socket — the inert/graceful-absence
 * posture the CI native no-config smoke relies on ([M14]). Boots Quarkus in-JVM; runs under Surefire
 * (headless library, CLAUDE.md §4 exception).
 */
@QuarkusTest
class BrowserToolProviderWiringIT {

    @Inject
    ToolProvider provider;   // resolves to the single BrowserToolProvider bean

    @Test
    void beanIsDiscoveredWithTheExpectedExtensionIdAndTools() {
        assertNotNull(provider);
        assertEquals("browser", provider.extensionId());
        Set<String> names = provider.tools().stream().map(ToolSpec::name).collect(Collectors.toSet());
        assertEquals(Set.of("browser.navigate", "browser.snapshot", "browser.extract", "browser.wait",
                "browser.click", "browser.type"), names);
    }

    @Test
    void invokeIsGracefulWithNoConfig() {
        // The test JVM has no ~/.forvum/tools/browser.json (forvum.home points at an absent path), so the
        // lazy CDP connect must surface the disabled state as a returned error string, never an exception
        // and never a socket open — this is what keeps the no-config native boot green.
        String result = provider.invoke("browser.snapshot", Map.of());
        assertTrue(result.startsWith("Browser tool error:"), result);
        assertTrue(result.contains("disabled"), result);
    }

    @Test
    void invokeOfAnUnknownToolThrows() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("browser.nope", Map.of()));
    }

    @Test
    void mutatingToolsAreConfirmGated() {
        Map<String, ToolSpec> byName = provider.tools().stream()
                .collect(Collectors.toMap(ToolSpec::name, s -> s));
        assertTrue(byName.get("browser.click").userConfirmRequired());
        assertTrue(byName.get("browser.type").userConfirmRequired());
        List.of("browser.navigate", "browser.snapshot", "browser.extract", "browser.wait")
                .forEach(n -> assertTrue(!byName.get(n).userConfirmRequired(),
                        n + " is read-only and must not be confirm-gated"));
    }
}
