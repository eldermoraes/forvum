package ai.forvum.engine.pairing;

import io.quarkus.runtime.annotations.RegisterForReflection;

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
 * rejected at the turn entry exactly like an unknown device (P2-PAIR-SCOPE #44 builds revocation reasons
 * + scope-upgrade approval on this flag).
 *
 * <p>This is a JSON-bound record in a Quarkus-bearing module, so it carries {@code @RegisterForReflection}
 * (CLAUDE.md section 5). Validation lives in {@link DeviceReader} where the source filename gives a
 * contextual error; the record stays a thin transport value.
 */
@RegisterForReflection
public record Device(String id, String token, String identityId, boolean revoked) {
}
