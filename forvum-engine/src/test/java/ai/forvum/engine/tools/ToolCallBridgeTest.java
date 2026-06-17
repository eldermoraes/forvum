package ai.forvum.engine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.InvocationStatus;
import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.model.InMemoryToolInvocationRecorder;
import ai.forvum.sdk.AbstractToolProvider;
import ai.forvum.sdk.ToolProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link ToolCallBridge}: the M18 seam that turns a model-emitted tool call into a
 * permission-gated, audited provider invocation (ULTRAPLAN section 5.5, Option A). The bridge parses the
 * tool-call JSON to a {@code Map}, resolves the owning provider via {@link ToolRegistry#providerFor}, and
 * runs the call inside {@link ToolExecutor} — so a tool outside the agent's belt is denied + audited and
 * the provider is never invoked, a permitted call is dispatched + audited {@code ok}, and a failing call
 * (including malformed arguments) is audited {@code error}. No reflection, no langchain4j in this seam.
 */
class ToolCallBridgeTest {

    private static final ToolSpec READ = new ToolSpec("a.read", "read a thing", PermissionScope.FS_READ, "{}");

    private static ToolProvider echoProvider() {
        return new AbstractToolProvider() {
            @Override
            public String extensionId() {
                return "a";
            }

            @Override
            public List<ToolSpec> tools() {
                return List.of(READ);
            }

            @Override
            public String invoke(String toolName, Map<String, Object> arguments) {
                return "read=" + arguments.get("path");
            }
        };
    }

    private ToolCallBridge bridge(InMemoryToolInvocationRecorder recorder, ToolProvider... providers) {
        ToolRegistry registry = new ToolRegistry();
        for (ToolProvider provider : providers) {
            registry.register(provider);
        }
        ToolExecutor executor = new ToolExecutor();
        executor.recorder = recorder;
        ToolCallBridge bridge = new ToolCallBridge();
        bridge.registry = registry;
        bridge.toolExecutor = executor;
        bridge.mapper = new ObjectMapper();
        return bridge;
    }

    @Test
    void permittedCallParsesArgumentsDispatchesToProviderAndAuditsOk() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        ToolCallBridge bridge = bridge(recorder, echoProvider());

        String result = bridge.dispatch("sess-1", new AgentId("main"), List.of(READ),
                "a.read", "{\"path\":\"notes.md\"}");

        assertEquals("read=notes.md", result, "the parsed argument reached the provider");
        assertEquals(1, recorder.invocations().size());
        assertSame(InvocationStatus.OK, recorder.invocations().get(0).status());
    }

    @Test
    void toolOutsideTheBeltIsDeniedAndTheProviderIsNeverInvoked() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        ToolProvider mustNotRun = new AbstractToolProvider() {
            @Override
            public String extensionId() {
                return "a";
            }

            @Override
            public List<ToolSpec> tools() {
                return List.of(READ);
            }

            @Override
            public String invoke(String toolName, Map<String, Object> arguments) {
                throw new AssertionError("a denied tool must not be invoked");
            }
        };
        ToolCallBridge bridge = bridge(recorder, mustNotRun);

        assertThrows(PermissionDeniedException.class, () -> bridge.dispatch(
                "sess-1", new AgentId("main"), List.of(READ), "a.write", "{}"));
        assertSame(InvocationStatus.DENIED, recorder.invocations().get(0).status());
    }

    @Test
    void specificationsForBuildsModelFacingSpecsFromToolSpecsWithoutReflection() {
        ToolCallBridge bridge = bridge(new InMemoryToolInvocationRecorder(), echoProvider());

        List<ToolSpecification> specs = bridge.specificationsFor(List.of(new ToolSpec(
                "fs.read", "Read a file", PermissionScope.FS_READ,
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\","
              + "\"description\":\"the path\"}},\"required\":[\"path\"]}")));

        assertEquals(1, specs.size());
        ToolSpecification spec = specs.get(0);
        assertEquals("fs.read", spec.name());
        assertEquals("Read a file", spec.description());
        assertTrue(spec.parameters().properties().containsKey("path"),
                "the JSON-schema property becomes a model-facing parameter");
        assertTrue(spec.parameters().required().contains("path"),
                "the required list is carried through");
    }

    @Test
    void specificationsForCarriesAnArrayOfStringProperty() {
        ToolCallBridge bridge = bridge(new InMemoryToolInvocationRecorder(), echoProvider());

        // shell.exec's argv: an array of strings (the cross-cutting array-schema support of PR-6).
        List<ToolSpecification> specs = bridge.specificationsFor(List.of(new ToolSpec(
                "shell.exec", "Run a process", PermissionScope.SHELL_EXEC,
                "{\"type\":\"object\",\"properties\":{"
              + "\"argv\":{\"type\":\"array\",\"description\":\"the argv vector\","
              + "\"items\":{\"type\":\"string\"}},"
              + "\"workingDir\":{\"type\":\"string\"}},"
              + "\"required\":[\"argv\"]}", true)));

        ToolSpecification spec = specs.get(0);
        var argv = spec.parameters().properties().get("argv");
        assertTrue(argv instanceof JsonArraySchema,
                "argv is offered to the model as a JSON array, got: " + argv);
        assertTrue(((JsonArraySchema) argv).items() instanceof JsonStringSchema,
                "the array items are strings (argv is an array of scalar strings)");
        assertTrue(spec.parameters().properties().get("workingDir") instanceof JsonStringSchema,
                "a sibling scalar string property is still a string schema");
        assertTrue(spec.parameters().required().contains("argv"),
                "the required array property is carried through");
    }

    @Test
    void malformedArgumentsOnAPermittedCallAreAuditedError() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        ToolCallBridge bridge = bridge(recorder, echoProvider());

        assertThrows(RuntimeException.class, () -> bridge.dispatch(
                "sess-1", new AgentId("main"), List.of(READ), "a.read", "{not valid json"));
        assertSame(InvocationStatus.ERROR, recorder.invocations().get(0).status());
    }
}
