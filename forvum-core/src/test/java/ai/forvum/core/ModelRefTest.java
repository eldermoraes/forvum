package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Example-based checks for {@link ModelRef} format, case rules, and failure modes (ULTRAPLAN section 4.3.5.1). */
class ModelRefTest {

    @Test
    void toStringIsProviderColonModel() {
        assertEquals("anthropic:claude-sonnet-4-6",
            new ModelRef("anthropic", "claude-sonnet-4-6").toString());
    }

    @Test
    void providerIsLowercasedInCanonicalConstructor() {
        assertEquals("anthropic", new ModelRef("ANTHROPIC", "Claude").provider());
    }

    @Test
    void modelCaseIsPreserved() {
        assertEquals("Claude", new ModelRef("anthropic", "Claude").model());
        assertNotEquals(ModelRef.parse("anthropic:foo"), ModelRef.parse("anthropic:Foo"));
    }

    @Test
    void parseKeepsOllamaTagAfterFirstColon() {
        ModelRef ref = ModelRef.parse("ollama:qwen3:1.7b");
        assertEquals("ollama", ref.provider());
        assertEquals("qwen3:1.7b", ref.model());
    }

    @Test
    void parseRejectsNull() {
        assertThrows(IllegalStateException.class, () -> ModelRef.parse(null));
    }

    @Test
    void parseRejectsBlank() {
        assertThrows(IllegalStateException.class, () -> ModelRef.parse("   "));
    }

    @Test
    void parseRejectsMissingColon() {
        assertThrows(IllegalStateException.class, () -> ModelRef.parse("ollama"));
    }

    @Test
    void parseRejectsBlankModel() {
        assertThrows(IllegalStateException.class, () -> ModelRef.parse("ollama:"));
    }

    @Test
    void parseRejectsBlankProvider() {
        assertThrows(IllegalStateException.class, () -> ModelRef.parse(":model"));
    }

    @Test
    void constructorRejectsWhitespaceEdges() {
        assertThrows(IllegalStateException.class, () -> new ModelRef(" anthropic", "x"));
        assertThrows(IllegalStateException.class, () -> new ModelRef("anthropic", "x "));
    }
}
