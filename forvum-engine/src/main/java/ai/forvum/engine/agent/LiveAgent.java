package ai.forvum.engine.agent;

import ai.forvum.core.Persona;

/**
 * An immutable, versioned snapshot of a registered agent: its parsed {@link AgentSpec} plus a monotonic
 * {@code generation} stamp. The registry publishes a NEW instance on every hot reload (never mutates one),
 * and a turn leases ONE at entry (bound into {@link AgentRegistry#CURRENT_AGENT_SPEC}) so every read for
 * that turn observes a single, internally consistent generation regardless of concurrent reloads (#178).
 * The monotonic generation makes a delete+recreate of the same id distinguishable (ABA-safe).
 *
 * <p>Engine-local, built field-by-field, never Jackson-bound or serialized — so it carries no
 * {@code @RegisterForReflection} (the DR-8 DP-12 / {@code GraphTurnRequest} precedent).
 */
public record LiveAgent(long generation, AgentSpec spec) {

    public LiveAgent {
        if (spec == null) {
            throw new IllegalStateException("LiveAgent spec must be non-null.");
        }
    }

    /** The snapshot's persona (its {@link AgentSpec#persona()}). */
    public Persona persona() {
        return spec.persona();
    }
}
