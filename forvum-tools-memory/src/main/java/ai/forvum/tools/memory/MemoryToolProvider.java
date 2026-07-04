package ai.forvum.tools.memory;

import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.AbstractToolProvider;
import ai.forvum.sdk.ForvumExtension;
import ai.forvum.sdk.MemoryAccess;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * The #193 memory tool provider: contributes model-callable {@code memory.save} / {@code memory.recall} to
 * the engine's global ToolRegistry (which discovers this {@code @ApplicationScoped} bean via CDI) and
 * self-dispatches each by name (M18 Option A, no reflection) to the injected {@link MemoryAccess} seam,
 * whose engine implementation does the identity-scoped, filtered read/write over #175's local memory. The
 * engine's ToolExecutor gates permission ({@code MEMORY_READ} / {@code MEMORY_WRITE}) and audits every call;
 * this provider only dispatches an already-permitted call.
 */
@ForvumExtension
@ApplicationScoped
public class MemoryToolProvider extends AbstractToolProvider {

    @Inject
    MemoryAccess memory;

    @Override
    public String extensionId() {
        return "memory";
    }

    @Override
    public List<ToolSpec> tools() {
        return List.of(MemorySaveTool.SPEC, MemoryRecallTool.SPEC);
    }

    @Override
    public String invoke(String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case "memory.save" -> MemorySaveTool.save(
                    memory, stringArg(arguments, "key"), stringArg(arguments, "value"));
            case "memory.recall" -> MemoryRecallTool.recall(memory, stringArg(arguments, "query"));
            default -> throw new IllegalArgumentException(
                    "MemoryToolProvider does not contribute a tool named '" + toolName
                  + "'. It provides memory.save, memory.recall.");
        };
    }

    /** The required {@code String} argument {@code key}; the model is contractually obliged to supply it. */
    private static String stringArg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Missing required argument '" + key + "' for a memory tool call.");
        }
        return value.toString();
    }
}
