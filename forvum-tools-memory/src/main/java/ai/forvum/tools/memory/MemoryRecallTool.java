package ai.forvum.tools.memory;

import ai.forvum.core.MemoryHit;
import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.MemoryAccess;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The {@code memory.recall} tool (#193): retrieve durable facts relevant to a free-text query. Holds the
 * ToolSpec ({@link PermissionScope#MEMORY_READ}) and renders the seam's ranked hits as a plain text list for
 * the model; the actual identity-scoped retrieval is the engine's {@link MemoryAccess} implementation.
 */
public final class MemoryRecallTool {

    public static final ToolSpec SPEC = new ToolSpec(
            "memory.recall",
            "Retrieve durable facts relevant to a query from long-term memory (scoped to the current user). "
          + "Use to check what is remembered before answering from assumption.",
            PermissionScope.MEMORY_READ,
            "{\"type\":\"object\",\"properties\":{"
          + "\"query\":{\"type\":\"string\",\"description\":\"what to recall, in natural language\"}},"
          + "\"required\":[\"query\"]}");

    private MemoryRecallTool() {
    }

    static String recall(MemoryAccess memory, String query) {
        List<MemoryHit> hits = memory.recall(query);
        if (hits.isEmpty()) {
            return "No relevant memory found.";
        }
        return hits.stream()
                .map(hit -> "- " + hit.content())
                .collect(Collectors.joining("\n"));
    }
}
