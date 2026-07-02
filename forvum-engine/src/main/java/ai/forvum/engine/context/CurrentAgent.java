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

    /**
     * The originating user message of the turn, bound at the channel turn entry (P2-14 #39). The approval
     * machinery captures it on a parked confirm-required call so an approval orphaned by a process restart
     * can re-dispatch the turn from it (R1). Unbound for entries with no user prompt (cron), where
     * re-dispatch does not apply.
     */
    public static final ScopedValue<String> CURRENT_USER_MESSAGE = ScopedValue.newInstance();

    /**
     * The bound {@link #CURRENT_TURN} id, or {@code null} on entries that bind none (cron fires; worker
     * virtual threads, where {@code ScopedValue} does not inherit) — the #169 budget-exhaustion
     * correlation read, shared by the cost gate and the tool-budget counter so both paths always carry
     * the same turn id. ({@code ScopedValue.orElse(null)} throws, so the bound-check form is required.)
     */
    public static UUID currentTurnOrNull() {
        return CURRENT_TURN.isBound() ? CURRENT_TURN.get() : null;
    }

    private CurrentAgent() {
    }
}
