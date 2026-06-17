package ai.forvum.tools.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pins the browser {@link ToolSpec} contract (P2-1, #26): every tool carries {@link PermissionScope#WEB_BROWSE},
 * the read-only tools are NOT confirm-gated, and the mutating tools ARE — the only #39 integration this
 * module makes (the approval machinery itself lives in the engine; the tool only declares the flag).
 */
class BrowserToolsTest {

    private static final Set<String> READ_ONLY =
            Set.of("browser.navigate", "browser.snapshot", "browser.extract", "browser.wait");
    private static final Set<String> MUTATING = Set.of("browser.click", "browser.type");

    @Test
    void contributesExactlyTheSixBrowserTools() {
        Set<String> names = BrowserTools.ALL.stream().map(ToolSpec::name).collect(Collectors.toSet());
        assertEquals(Set.of("browser.navigate", "browser.snapshot", "browser.extract", "browser.wait",
                "browser.click", "browser.type"), names);
    }

    @Test
    void everyToolRequiresWebBrowse() {
        for (ToolSpec spec : BrowserTools.ALL) {
            assertEquals(PermissionScope.WEB_BROWSE, spec.requiredScope(),
                    spec.name() + " must require WEB_BROWSE");
        }
    }

    @Test
    void readOnlyToolsAreNotConfirmGated() {
        for (ToolSpec spec : BrowserTools.ALL) {
            if (READ_ONLY.contains(spec.name())) {
                assertFalse(spec.userConfirmRequired(),
                        spec.name() + " is read-only and must NOT require user confirmation");
            }
        }
    }

    @Test
    void mutatingToolsAreConfirmGated() {
        for (ToolSpec spec : BrowserTools.ALL) {
            if (MUTATING.contains(spec.name())) {
                assertTrue(spec.userConfirmRequired(),
                        spec.name() + " mutates the page and MUST require user confirmation (#39)");
            }
        }
    }

    @Test
    void navigateStaysUnconfirmedForUsability() {
        // The maintainer default (confirm-gate granularity): only the obvious mutators click/type gate;
        // navigate stays confirm=false. A regression flipping it would break usability.
        assertFalse(BrowserTools.NAVIGATE.userConfirmRequired());
    }

    @Test
    void everyToolCarriesAValidParametersJsonSchema() {
        for (ToolSpec spec : BrowserTools.ALL) {
            assertTrue(spec.parametersJsonSchema().contains("\"type\":\"object\""),
                    spec.name() + " declares an object parameters schema");
        }
    }

    @Test
    void allListIsImmutable() {
        List<ToolSpec> all = BrowserTools.ALL;
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> all.add(BrowserTools.NAVIGATE));
    }
}
