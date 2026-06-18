package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import ai.forvum.core.ModelRef;
import ai.forvum.core.Persona;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.graph.CycleSpec;

/**
 * {@link AgentSpec} composition (DR-8 DP-2): a {@link Persona} plus an optional engine-side
 * {@link CycleSpec}. Plain Surefire — no Quarkus boot.
 */
class AgentSpecTest {

    private static final Persona PERSONA = new Persona(new AgentId("main"), "prompt", List.of(),
            new ModelRef("ollama", "qwen3:1.7b"), null, null, null, null);

    @Test
    void composesAPersonaAndACycle() {
        CycleSpec cycle = new CycleSpec(List.of("reflect", "revise"), 2, null);
        AgentSpec spec = new AgentSpec(PERSONA, cycle);
        assertEquals(PERSONA, spec.persona());
        assertEquals(cycle, spec.cycle());
    }

    @Test
    void acceptsANullCycle() {
        AgentSpec spec = new AgentSpec(PERSONA, null);
        assertNull(spec.cycle(), "no declared cycle = the standard supervisor graph");
    }

    @Test
    void rejectsANullPersona() {
        assertThrows(IllegalStateException.class, () -> new AgentSpec(null, null));
    }
}
