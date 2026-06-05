package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ToolSpec;
import ai.forvum.engine.tools.ToolRegistry;

import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Regression guard for tool-module CDI wiring on the assembled {@code forvum-app} classpath (the M12
 * lesson: a tool/provider module's own contract test passes on its single-module classpath, so a broken
 * app dependency, {@code beans.xml} discovery mode, or {@code plugin.json} would otherwise ship green).
 * On app startup the engine's {@link ToolRegistry} discovers every {@code @ForvumExtension
 * @ApplicationScoped} {@code ToolProvider}; this asserts the M14 filesystem module's
 * {@code fs.read}/{@code fs.write}/{@code fs.list} are actually registered. Non-live (only needs boot),
 * intentionally not {@code @Tag("live")} so the default build catches a wiring regression.
 */
@QuarkusTest
class ToolRegistryDiscoveryInAppClasspathTest {

    @Inject
    ToolRegistry registry;

    @Test
    void theFilesystemToolsAreDiscoveredOnTheAssembledClasspath() {
        Set<String> names = registry.all().stream().map(ToolSpec::name).collect(Collectors.toSet());

        assertTrue(names.containsAll(Set.of("fs.read", "fs.write", "fs.list")),
                "the filesystem tool module must be discovered + registered on the app classpath, found: " + names);
    }
}
