package ai.forvum.tools.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.MemoryHit;
import ai.forvum.core.MemoryTier;
import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.MemoryAccess;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link MemoryToolProvider} contract (#193): it contributes {@code memory.save} (MEMORY_WRITE) and
 * {@code memory.recall} (MEMORY_READ), self-dispatches each by name to the injected {@link MemoryAccess}
 * seam, formats the result for the model, and rejects an unknown tool or a missing argument. A pure unit
 * test with a fake {@code MemoryAccess} — the seam's engine wiring is proven by {@code EngineMemoryAccessIT}.
 */
class MemoryToolProviderTest {

    private MemoryToolProvider providerWith(FakeMemoryAccess memory) {
        MemoryToolProvider provider = new MemoryToolProvider();
        provider.memory = memory;
        return provider;
    }

    @Test
    void contributesSaveAndRecallWithTheCorrectScopes() {
        List<ToolSpec> tools = providerWith(new FakeMemoryAccess()).tools();
        assertEquals(2, tools.size());

        ToolSpec save = tools.stream().filter(t -> t.name().equals("memory.save")).findFirst().orElseThrow();
        assertEquals(PermissionScope.MEMORY_WRITE, save.requiredScope());
        assertFalse(save.userConfirmRequired(), "memory.save is low-friction — no approval gate (issue #193)");

        ToolSpec recall = tools.stream().filter(t -> t.name().equals("memory.recall")).findFirst().orElseThrow();
        assertEquals(PermissionScope.MEMORY_READ, recall.requiredScope());
    }

    @Test
    void saveDispatchesToTheSeamAndConfirms() {
        FakeMemoryAccess memory = new FakeMemoryAccess();
        String result = providerWith(memory).invoke("memory.save", Map.of("key", "fav-color", "value", "blue"));

        assertEquals("blue", memory.saved.get("fav-color"), "the value reaches the MemoryAccess seam");
        assertTrue(result.toLowerCase().contains("saved"), "the model gets a confirmation");
    }

    @Test
    void saveReportsWhenTheFilterBlocksTheValue() {
        FakeMemoryAccess memory = new FakeMemoryAccess();
        memory.blockSaves = true;
        String result = providerWith(memory).invoke("memory.save", Map.of("key", "k", "value", "v"));

        assertTrue(memory.saved.isEmpty(), "a blocked value is not stored");
        assertTrue(result.toLowerCase().contains("not saved") || result.toLowerCase().contains("blocked"),
                "the model is told the value was not saved");
    }

    @Test
    void recallDispatchesToTheSeamAndFormatsHits() {
        FakeMemoryAccess memory = new FakeMemoryAccess();
        memory.recallResult = List.of(
                new MemoryHit(MemoryTier.SEMANTIC, "the user likes blue", 0.9, "semantic:fav-color"));
        String result = providerWith(memory).invoke("memory.recall", Map.of("query", "favorite color"));

        assertEquals("favorite color", memory.lastRecallQuery, "the query reaches the seam");
        assertTrue(result.contains("the user likes blue"), "the hit content is rendered for the model");
    }

    @Test
    void recallReportsWhenNothingIsFound() {
        String result = providerWith(new FakeMemoryAccess()).invoke("memory.recall", Map.of("query", "x"));
        assertTrue(result.toLowerCase().contains("no relevant memory") || result.toLowerCase().contains("nothing"),
                "an empty recall tells the model nothing matched, rather than an empty string");
    }

    @Test
    void invokeRejectsAnUnknownToolName() {
        assertThrows(IllegalArgumentException.class,
                () -> providerWith(new FakeMemoryAccess()).invoke("memory.forget", Map.of()));
    }

    @Test
    void invokeRejectsAMissingRequiredArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> providerWith(new FakeMemoryAccess()).invoke("memory.save", Map.of("key", "k")));
    }

    /** A deterministic in-memory {@link MemoryAccess} double — no engine, no SQLite, no embedding. */
    static final class FakeMemoryAccess implements MemoryAccess {
        final Map<String, String> saved = new LinkedHashMap<>();
        boolean blockSaves = false;
        List<MemoryHit> recallResult = List.of();
        String lastRecallQuery;

        @Override
        public boolean save(String key, String value) {
            if (blockSaves) {
                return false;
            }
            saved.put(key, value);
            return true;
        }

        @Override
        public List<MemoryHit> recall(String query) {
            lastRecallQuery = query;
            return recallResult;
        }
    }
}
