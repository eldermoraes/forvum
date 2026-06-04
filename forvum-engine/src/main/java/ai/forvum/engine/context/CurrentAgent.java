package ai.forvum.engine.context;

import ai.forvum.core.id.AgentId;

import java.util.UUID;

/**
 * The per-turn scoped bindings that drive the {@code @AgentScoped} context.
 *
 * <p>{@code CURRENT_AGENT} is the context's identity key; {@code CURRENT_TURN} carries the turn UUID
 * for structural correlation (ULTRAPLAN sections 5.1 and 4.3.1). Both are {@link ScopedValue}s (final
 * in JDK 25), bound stack-scoped via {@code ScopedValue.where(CURRENT_AGENT, id).call(body)} so the
 * binding is torn down when the lambda returns — even on exception — and carries correctly across
 * virtual threads without {@code InheritableThreadLocal} semantics.
 *
 * <p>M6 only defines these bindings. Persisting {@code turn_id} (Flyway V2) is a later milestone.
 */
public final class CurrentAgent {

    /** Identity key for the {@code @AgentScoped} context. */
    public static final ScopedValue<AgentId> CURRENT_AGENT = ScopedValue.newInstance();

    /** Turn correlation id, bound nested under {@link #CURRENT_AGENT} at turn start. */
    public static final ScopedValue<UUID> CURRENT_TURN = ScopedValue.newInstance();

    private CurrentAgent() {
    }
}
