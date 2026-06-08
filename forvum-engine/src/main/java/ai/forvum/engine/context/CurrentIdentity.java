package ai.forvum.engine.context;

import ai.forvum.core.PermissionScope;

import java.util.Set;

/**
 * The per-turn scoped binding carrying the calling identity's EFFECTIVE permission scopes (P2-11 RBAC,
 * ULTRAPLAN section 4.3.4): the union of the scope-sets of the identity's declared roles, resolved once
 * at turn entry. The engine's {@code ToolExecutor} reads it to enforce that a tool's
 * {@link ai.forvum.core.ToolSpec#requiredScope()} is granted — a second gate orthogonal to belt
 * membership.
 *
 * <p>Like {@link CurrentAgent}, it is a {@link ScopedValue} (final in JDK 25), bound stack-scoped at the
 * turn entry ({@code TurnService.dispatch} for channels/CLI, {@code CronScheduler.fire} for the
 * distinguished {@code cron} role) so it tears down when the turn lambda returns and carries across the
 * turn's synchronous call chain on the same virtual thread.
 *
 * <p>When UNBOUND — a lower-level unit test, or any caller outside a turn entry — the executor falls back
 * to belt-only authorization (the pre-P2-11 behavior). Every production turn entry binds it, so the scope
 * gate is always active in production; sub-agent workers run a single direct generation with no tool loop
 * (M18), so they never reach the executor.
 */
public final class CurrentIdentity {

    /** The caller's effective permission scopes for the current turn (P2-11). */
    public static final ScopedValue<Set<PermissionScope>> CURRENT_EFFECTIVE_SCOPES = ScopedValue.newInstance();

    private CurrentIdentity() {
    }
}
