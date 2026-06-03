package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import ai.forvum.core.budget.CostBudget;
import ai.forvum.core.budget.DayWindow;
import ai.forvum.core.id.AgentId;

/**
 * {@link Persona} structural shape (section 4.3 backfill): required id/systemPrompt/allowedTools/primaryModel;
 * nullable parent/costBudget/toolBudget; allowedTools immutable; {@code FallbackChain}/{@code MemoryPolicy}
 * deliberately absent (deferred to DR-4c/DR-5).
 */
class PersonaTest {

    private static final AgentId ID = new AgentId("assistant");
    private static final ModelRef MODEL = new ModelRef("ollama", "qwen3:1.7b");

    @Test
    void acceptsMinimalValidWithNullableFieldsNull() {
        Persona p = new Persona(ID, "You are helpful", List.of("fs.*"), MODEL, null, null, null);
        assertEquals(ID, p.id());
        assertEquals(MODEL, p.primaryModel());
        assertNull(p.parent());
        assertNull(p.costBudget());
        assertNull(p.toolBudget());
    }

    @Test
    void acceptsFullyPopulated() {
        CostBudget budget = new CostBudget(BigDecimal.ONE, null, new DayWindow(ZoneId.of("UTC")));
        Persona p = new Persona(ID, "prompt", List.of("fs.read"), MODEL, new AgentId("root"), budget, 10L);
        assertEquals(new AgentId("root"), p.parent());
        assertEquals(budget, p.costBudget());
        assertEquals(10L, p.toolBudget());
    }

    @Test
    void allowedToolsAreImmutable() {
        Persona p = new Persona(ID, "p", List.of("a"), MODEL, null, null, null);
        assertThrows(UnsupportedOperationException.class, () -> p.allowedTools().add("b"));
    }

    @Test
    void allowedToolsAreDefensivelyCopied() {
        List<String> source = new ArrayList<>();
        source.add("a");
        Persona p = new Persona(ID, "p", source, MODEL, null, null, null);
        source.add("b");
        assertEquals(1, p.allowedTools().size());
    }

    @Test
    void allowsEmptyAllowedTools() {
        new Persona(ID, "p", List.of(), MODEL, null, null, null);
    }

    @Test
    void rejectsNullToolEntryWithTriageException() {
        // A malformed JSON array like ["fs.read", null] must surface the module idiom
        // (IllegalStateException), not a bare NullPointerException from List.copyOf.
        assertThrows(IllegalStateException.class,
            () -> new Persona(ID, "p", Arrays.asList("fs.read", null), MODEL, null, null, null));
    }

    @Test
    void rejectsInvalid() {
        assertThrows(IllegalStateException.class, () -> new Persona(null, "p", List.of(), MODEL, null, null, null));
        assertThrows(IllegalStateException.class, () -> new Persona(ID, null, List.of(), MODEL, null, null, null));
        assertThrows(IllegalStateException.class, () -> new Persona(ID, " ", List.of(), MODEL, null, null, null));
        assertThrows(IllegalStateException.class, () -> new Persona(ID, "p", null, MODEL, null, null, null));
        assertThrows(IllegalStateException.class, () -> new Persona(ID, "p", List.of(), null, null, null, null));
        assertThrows(IllegalStateException.class, () -> new Persona(ID, "p", List.of(), MODEL, null, null, -1L));
    }
}
