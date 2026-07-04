package ai.forvum.tools.memory;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.MemoryAccess;

/**
 * The {@code memory.save} tool (#193): deliberately persist a durable fact for later recall. Holds the
 * ToolSpec ({@link PermissionScope#MEMORY_WRITE}, no approval gate) and formats the seam's boolean outcome
 * for the model; the actual filter+embed+upsert is the engine's {@link MemoryAccess} implementation.
 */
public final class MemorySaveTool {

    public static final ToolSpec SPEC = new ToolSpec(
            "memory.save",
            "Persist a durable fact under a short key so it can be recalled in a later turn or session. Use "
          + "for stable user facts/preferences the assistant should remember; scoped to the current user.",
            PermissionScope.MEMORY_WRITE,
            "{\"type\":\"object\",\"properties\":{"
          + "\"key\":{\"type\":\"string\",\"description\":\"a short stable identifier for the fact\"},"
          + "\"value\":{\"type\":\"string\",\"description\":\"the fact to remember\"}},"
          + "\"required\":[\"key\",\"value\"]}");

    private MemorySaveTool() {
    }

    static String save(MemoryAccess memory, String key, String value) {
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            return "Both a non-empty key and value are required to save a memory.";
        }
        boolean stored = memory.save(key, value);
        return stored
                ? "Saved fact '" + key + "'."
                : "The value was blocked by the content filter and not saved.";
    }
}
