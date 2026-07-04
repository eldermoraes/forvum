package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.id.AgentId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

/**
 * #177: the ephemeral worker id allocator. Each spawn gets a fresh runtime id ({@code <label>~<uuid8>}),
 * so a model-suggested label can never collide with a persistent (file-declared) agent or with another
 * worker — collision-safety by construction. Pure unit test (no CDI boot).
 */
class EphemeralAgentIdTest {

    @Test
    void idCarriesTheSanitizedLabelAndAUniqueSuffix() {
        AgentId id = EphemeralAgentId.forLabel("researcher");
        String value = id.value();
        assertTrue(value.startsWith("researcher~"), "the label is preserved as a readable prefix: " + value);
        assertEquals(8, value.substring(value.indexOf('~') + 1).length(), "an 8-char uuid suffix: " + value);
    }

    @Test
    void repeatedAllocationForTheSameLabelYieldsDistinctIds() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            AgentId id = EphemeralAgentId.forLabel("dup");
            assertTrue(seen.add(id.value()), "collision on allocation " + i + ": " + id.value());
        }
    }

    @Test
    void twoAllocationsAreNeverEqual() {
        assertNotEquals(EphemeralAgentId.forLabel("x"), EphemeralAgentId.forLabel("x"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "a/b", "..", "worker with spaces", "@#$%", "\t\n"})
    void aMalformedLabelStillProducesAValidAgentId(String label) {
        AgentId id = EphemeralAgentId.forLabel(label);
        // AgentId's own canonical constructor is the contract: non-blank, no edge whitespace. Reaching here
        // (no thrown IllegalStateException) proves the allocator always yields a valid id.
        assertFalse(id.value().isBlank());
        assertEquals(id.value().strip(), id.value(), "no leading/trailing whitespace");
        assertTrue(id.value().contains("~"), "keeps the ephemeral marker even for an empty label: " + id.value());
    }

    @Test
    void aNullLabelIsTolerated() {
        AgentId id = EphemeralAgentId.forLabel(null);
        assertTrue(id.value().startsWith("worker~"), "a null label falls back to 'worker': " + id.value());
    }

    @Test
    void anOverlongLabelIsTruncatedBeforeTheSuffix() {
        String longLabel = "a".repeat(200);
        AgentId id = EphemeralAgentId.forLabel(longLabel);
        String base = id.value().substring(0, id.value().indexOf('~'));
        assertTrue(base.length() <= 24, "the label base is bounded: " + base.length());
    }
}
