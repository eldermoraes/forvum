package ai.forvum.engine.pairing;

import ai.forvum.core.PermissionScope;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Set;

/**
 * A paired device endpoint, declared in {@code $FORVUM_HOME/devices/<id>.json} (P2-4, ULTRAPLAN
 * section 7.2 item 4) — "fixed code, configurable behavior": a second device is paired by dropping a
 * file, no recompile. The {@code id} is the channel/device endpoint id (e.g. {@code web}, {@code tui},
 * {@code telegram}); {@code token} is the pairing shared-secret the device presents; {@code identityId}
 * is the {@code Identity} the device reuses, RECORDED here for the P2-PAIR-SCOPE #44 CLI/management
 * surface. The memory namespace is shared by the existing {@code IdentityResolver} channel-account
 * mapping — {@code TurnService} resolves the session identity from {@code (channelId, nativeUserId)} —
 * not by this field; this record is a thin transport value, the binding lives in {@code IdentityResolver}.
 *
 * <p>A {@code revoked} device is declared-but-disabled — its file stays for audit/re-pairing yet it is
 * rejected at the turn entry exactly like an unknown device.
 *
 * <p><strong>Scope governance (P2-PAIR-SCOPE #44).</strong> {@code requestedScopes} are the capability
 * scopes the device declares it wants; {@code approvedScopes} are the subset the owner granted via
 * {@code forvum pair approve}; {@code decisionReason} records the reason code from the last
 * approve/reject. A scope upgrade is "requested but not yet approved" — {@link #hasScopeDrift()} — which
 * {@code forvum devices} and {@code forvum doctor} surface. v0.5 makes governance + drift visible; the
 * turn-path enforcement of {@code approvedScopes} lands with the #39 approval dashboard (it is not wired
 * into the turn here). All three fields are backward compatible: a device file without them keeps the
 * 4-arg shape (empty scope sets, no reason), so an existing install needs no migration.
 *
 * <p>This is a JSON-bound record in a Quarkus-bearing module, so it carries {@code @RegisterForReflection}
 * (CLAUDE.md section 5). Validation lives in {@link DeviceSpecReader} where the source filename gives a
 * contextual error; the record stays a thin transport value.
 */
@RegisterForReflection
public record Device(String id, String token, String identityId, boolean revoked,
                     Set<PermissionScope> requestedScopes, Set<PermissionScope> approvedScopes,
                     String decisionReason) {

    public Device {
        requestedScopes = requestedScopes == null ? Set.of() : Set.copyOf(requestedScopes);
        approvedScopes = approvedScopes == null ? Set.of() : Set.copyOf(approvedScopes);
    }

    /** Backward-compatible 4-arg form (no scope governance) — the pre-#44 shape every prior caller used. */
    public Device(String id, String token, String identityId, boolean revoked) {
        this(id, token, identityId, revoked, Set.of(), Set.of(), null);
    }

    /**
     * True when the device requested a scope it has not been granted — a pending scope upgrade. An empty
     * request never drifts; a device whose {@code approvedScopes} already cover every requested scope is
     * settled.
     */
    public boolean hasScopeDrift() {
        return !approvedScopes.containsAll(requestedScopes);
    }
}
