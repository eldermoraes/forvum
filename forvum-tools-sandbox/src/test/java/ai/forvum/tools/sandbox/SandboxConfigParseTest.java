package ai.forvum.tools.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

/**
 * Pure parser tests for {@link SandboxConfig#parse(JsonNode)} and the {@link SandboxConfig.Spec}
 * default-deny posture. No CDI, no filesystem, no container runtime — the JsonNode tree-walk maps
 * {@code tools/sandbox.json} into the {@code Spec} record.
 */
class SandboxConfigParseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private SandboxConfig.Spec parse(String json) throws Exception {
        return SandboxConfig.parse(mapper.readTree(json));
    }

    @Test
    void aFullConfigIsParsed() throws Exception {
        SandboxConfig.Spec spec = parse("""
            {
              "image": "python:3.12-slim",
              "runtime": "podman",
              "network": "none",
              "user": "65534:65534",
              "cpus": "2",
              "memory": "512m",
              "containerWorkdir": "/work",
              "interpreter": ["python3", "-c"],
              "timeoutSeconds": 30
            }
            """);

        assertEquals("python:3.12-slim", spec.image());
        assertEquals(Optional.of("podman"), spec.runtime());
        assertEquals("none", spec.network());
        assertFalse(spec.allowNetwork(), "allowNetwork defaults to false");
        assertEquals("65534:65534", spec.user());
        assertEquals("2", spec.cpus());
        assertEquals("512m", spec.memory());
        assertEquals("/work", spec.containerWorkdir());
        assertEquals(List.of("python3", "-c"), spec.interpreter());
        assertEquals(30, spec.timeoutSeconds());
    }

    @Test
    void defaultsApplyWhenFieldsAreOmitted() throws Exception {
        SandboxConfig.Spec spec = parse("{\"image\":\"alpine:3.20\"}");

        assertEquals("alpine:3.20", spec.image());
        assertEquals(Optional.empty(), spec.runtime(), "runtime auto-detects when omitted");
        assertEquals(SandboxConfig.DEFAULT_NETWORK, spec.network());
        assertEquals(SandboxConfig.DEFAULT_USER, spec.user());
        assertEquals(SandboxConfig.DEFAULT_CPUS, spec.cpus());
        assertEquals(SandboxConfig.DEFAULT_MEMORY, spec.memory());
        assertEquals("/workspace", spec.containerWorkdir());
        assertEquals(SandboxConfig.DEFAULT_INTERPRETER, spec.interpreter());
        assertEquals(SandboxConfig.DEFAULT_TIMEOUT_SECONDS, spec.timeoutSeconds());
    }

    @Test
    void anEmptyOrNonObjectConfigIsFailClosed() throws Exception {
        assertTrue(parse("{}").image().isBlank(), "no image = fail-closed");
        assertTrue(parse("[]").image().isBlank(), "a non-object root is fail-closed");
        assertTrue(parse("null").image().isBlank(), "a null root is fail-closed");
    }

    @Test
    void aNonPositiveTimeoutFallsBackToTheDefault() throws Exception {
        assertEquals(SandboxConfig.DEFAULT_TIMEOUT_SECONDS,
                parse("{\"image\":\"x\",\"timeoutSeconds\":0}").timeoutSeconds());
        assertEquals(SandboxConfig.DEFAULT_TIMEOUT_SECONDS,
                parse("{\"image\":\"x\",\"timeoutSeconds\":-5}").timeoutSeconds());
    }

    @Test
    void allowNetworkIsHonoredWhenTrue() throws Exception {
        assertTrue(parse("{\"image\":\"x\",\"allowNetwork\":true}").allowNetwork());
    }

    @Test
    void requireRunnableThrowsOnAFailClosedSpec() {
        assertThrows(SandboxExecException.class, () -> SandboxConfig.Spec.failClosed().requireRunnable(),
                "fail-closed (no image) refuses every invocation");
    }

    @Test
    void requireRunnablePassesWithAnImage() throws Exception {
        // Does not throw.
        parse("{\"image\":\"busybox\"}").requireRunnable();
    }

    @Test
    void aBlankImageStringIsTreatedAsFailClosed() throws Exception {
        assertThrows(SandboxExecException.class,
                () -> parse("{\"image\":\"   \"}").requireRunnable(),
                "a whitespace-only image is not a real image");
    }
}
