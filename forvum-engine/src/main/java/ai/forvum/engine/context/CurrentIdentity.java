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

    /**
     * The owning identity id for the current turn — the multi-user tenant key (#53). Bound at the turn
     * entry to the resolved identity when {@code forvum.multi-user.enabled}, or to {@code "default"}
     * otherwise (single-user, byte-identical). Read by {@code AgentMemory} to scope per-identity facts;
     * when UNBOUND (a lower-level unit test) it falls back to {@code "default"}.
     */
    public static final ScopedValue<String> CURRENT_IDENTITY_ID = ScopedValue.newInstance();

    /**
     * The caller's effective scopes CARRIED ACROSS a nested relayed dispatch (#189 audit, overriding the
     * plan's OQ1): {@code sessions.send} starts a brand-new turn whose scope resolution would otherwise
     * re-derive from the target session's identity — silently DROPPING the #166 device-scope cap the
     * calling turn ran under (a paired device approved for {@code SESSION_WRITE} could relay into a
     * session whose turn then re-resolves wider scopes). The relaying seam binds this to the caller's
     * {@link #CURRENT_EFFECTIVE_SCOPES} around the nested dispatch, and {@code TurnService} INTERSECTS it
     * into the nested turn's effective scopes — the cap can only ever restrict, never widen (#167).
     * Unbound (every non-relayed turn) it is a no-op.
     */
    public static final ScopedValue<Set<PermissionScope>> INHERITED_SCOPE_CAP = ScopedValue.newInstance();

    /** The single-user / shared team-skill namespace (the migration default). */
    public static final String DEFAULT_IDENTITY = "default";

    /** The current turn's tenant identity, or {@link #DEFAULT_IDENTITY} when unbound (lower-level callers). */
    public static String currentIdentityId() {
        return CURRENT_IDENTITY_ID.isBound() ? CURRENT_IDENTITY_ID.get() : DEFAULT_IDENTITY;
    }

    private CurrentIdentity() {
    }
}
