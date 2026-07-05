package ai.forvum.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.tools.web.WebToolConfig.Spec;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@code WebToolConfig} reads {@code tools/web.json}: the Brave API key, the {@code allowPrivateNetwork}
 * egress opt-in, and the absent-file → empty spec (so the module is inert with no {@code ~/.forvum/}).
 * Plain POJO tests — no Quarkus boot needed.
 */
class WebToolConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesBraveKeyAndEgressMode() throws Exception {
        Spec spec = WebToolConfig.parse(MAPPER.readTree(
                "{ \"braveApiKey\": \"BSA-secret\", \"allowPrivateNetwork\": true }"));

        assertEquals(Optional.of("BSA-secret"), spec.braveApiKey());
        assertTrue(spec.allowPrivateNetwork());
    }

    @Test
    void defaultsToNoKeyAndStrictEgress() {
        Spec spec = WebToolConfig.parse(MAPPER.createObjectNode());

        assertTrue(spec.braveApiKey().isEmpty(), "no key configured");
        assertFalse(spec.allowPrivateNetwork(), "egress is strict (private blocked) by default");
        assertTrue(spec.allowedPorts().isEmpty(),
                "no allowedPorts → empty (EgressGuard falls back to its {80,443,default} default)");
        assertTrue(spec.searchBackend().isEmpty(), "no backend → the precedence default applies");
    }

    @Test
    void parsesTheSearchBackend() throws Exception {
        Spec spec = WebToolConfig.parse(MAPPER.readTree("{ \"backend\": \"duckduckgo\" }"));
        assertEquals(Optional.of("duckduckgo"), spec.searchBackend());
    }

    @Test
    void keepsAnUnknownBackendValueRaw() throws Exception {
        // The reader is lenient: it does NOT validate the value (so a bad backend cannot break web.fetch,
        // which shares this reader); resolution happens on the web.search path.
        Spec spec = WebToolConfig.parse(MAPPER.readTree("{ \"backend\": \"bing\" }"));
        assertEquals(Optional.of("bing"), spec.searchBackend());
    }

    @Test
    void treatsBlankBackendAsAbsent() throws Exception {
        Spec spec = WebToolConfig.parse(MAPPER.readTree("{ \"backend\": \"   \" }"));
        assertTrue(spec.searchBackend().isEmpty(), "a blank backend is treated as unset");
    }

    @Test
    void emptySpecHasNoBackend() {
        assertTrue(Spec.empty().searchBackend().isEmpty());
    }

    @Test
    void threeArgConstructorDefaultsTheBackendToAbsent() {
        // The [#170] trailing-component recipe: pre-#192 3-arg construction still compiles and defaults
        // the backend to empty (this construction IS the backward-compat regression assertion).
        Spec spec = new Spec(Optional.of("k"), true, java.util.Set.of(8080));
        assertTrue(spec.searchBackend().isEmpty());
        assertEquals(Optional.of("k"), spec.braveApiKey());
    }

    @Test
    void parsesAllowedPortsArray() throws Exception {
        Spec spec = WebToolConfig.parse(MAPPER.readTree("{ \"allowedPorts\": [80, 443, 8080] }"));
        assertEquals(java.util.Set.of(80, 443, 8080), spec.allowedPorts());
    }

    @Test
    void ignoresNonIntegerAllowedPortEntries() throws Exception {
        Spec spec = WebToolConfig.parse(MAPPER.readTree(
                "{ \"allowedPorts\": [80, \"redis\", true, 8443] }"));
        assertEquals(java.util.Set.of(80, 8443), spec.allowedPorts(), "only integer ports are kept");
    }

    @Test
    void nonArrayAllowedPortsIsEmpty() throws Exception {
        Spec spec = WebToolConfig.parse(MAPPER.readTree("{ \"allowedPorts\": 8080 }"));
        assertTrue(spec.allowedPorts().isEmpty(), "a non-array allowedPorts is ignored (falls back to default)");
    }

    @Test
    void treatsBlankKeyAsAbsent() throws Exception {
        Spec spec = WebToolConfig.parse(MAPPER.readTree("{ \"braveApiKey\": \"   \" }"));
        assertTrue(spec.braveApiKey().isEmpty(), "a blank key is treated as unset");
    }

    @Test
    void nullTreeIsEmpty() {
        Spec spec = WebToolConfig.parse(null);
        assertTrue(spec.braveApiKey().isEmpty());
        assertFalse(spec.allowPrivateNetwork());
    }

    @Test
    void absentFileReturnsEmptySpec(@TempDir Path dir) {
        WebToolConfig config = new WebToolConfig(dir.resolve("tools").resolve("web.json"));
        Spec spec = config.read();
        assertTrue(spec.braveApiKey().isEmpty(), "no tools/web.json → inert");
        assertFalse(spec.allowPrivateNetwork());
    }

    @Test
    void readsFromFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tools").resolve("web.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ \"braveApiKey\": \"k\" }");

        Spec spec = new WebToolConfig(file).read();
        assertEquals(Optional.of("k"), spec.braveApiKey());
    }

    @Test
    void malformedFileThrows(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tools").resolve("web.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ not json");

        WebToolConfig config = new WebToolConfig(file);
        assertThrows(UncheckedIOException.class, config::read,
                "a malformed config is a real misconfiguration the operator must see");
    }

    @Test
    void resolveHomeUsesForvumHomeWhenSet() {
        Path home = WebToolConfig.resolveHome(Optional.of("/tmp/fv-home"), "/home/u");
        assertEquals(Path.of("/tmp/fv-home").toAbsolutePath().normalize(), home);
    }

    @Test
    void resolveHomeFallsBackToUserHome() {
        Path home = WebToolConfig.resolveHome(Optional.empty(), "/home/u");
        assertEquals(Path.of("/home/u").resolve(".forvum").toAbsolutePath().normalize(), home);
    }
}
