package ai.forvum.core;

/**
 * A transport-neutral device credential a channel adapter carries into the inbound turn (#166): the
 * claimed device endpoint id plus the pairing shared-secret {@code token} the connection presented. The
 * engine's {@code TurnService} authenticates it against the paired {@code Device} (timing-safe token
 * compare, channel bind {@code deviceId == channelId}, revocation) BEFORE the responder runs, then
 * intersects the device's {@code approvedScopes} into the turn's effective scopes.
 *
 * <p><strong>Secret hygiene (#166 acceptance).</strong> The token must never be logged, metered,
 * persisted, or echoed in an exception — so {@link #toString()} redacts it and this record is safe to log
 * or carry through an error path.
 *
 * <p>Channels that present no per-connection credential pass {@link #ABSENT}, which keeps the
 * backward-compatible P2-4 paired-by-existence behavior (the operator/local/exempt path). A
 * <em>present</em> credential ({@link #present()}) opts the turn into #166 token authentication.
 *
 * <p>Layer-0 ({@code forvum-core}) record: it carries no {@code @RegisterForReflection} itself
 * ({@code forvum-core} bans {@code io.quarkus*}, CLAUDE.md section 5); the engine registers it in
 * {@code CoreReflectionRegistration} (section 6.3).
 */
public record DeviceCredential(String deviceId, String token) {

    /** The sentinel a channel with no per-connection credential presents (operator/local/exempt path). */
    public static final DeviceCredential ABSENT = new DeviceCredential("", "");

    public DeviceCredential {
        // The device id is bound against the channel id, so normalize stray whitespace; the token is the
        // shared secret and is preserved verbatim so the constant-time compare is byte-exact.
        deviceId = deviceId == null ? "" : deviceId.strip();
        token = token == null ? "" : token;
    }

    /** True when no device id was claimed — the paired-by-existence / exempt path (the {@link #ABSENT} case). */
    public boolean isAbsent() {
        return deviceId.isBlank();
    }

    /** True when a device id was claimed — the credential opts the turn into #166 token authentication. */
    public boolean present() {
        return !isAbsent();
    }

    /**
     * Redacts the token so a logged, metered, or error-echoed credential never leaks the shared secret
     * (#166 acceptance). The non-secret {@code deviceId} is rendered as-is; a present token becomes
     * {@code <redacted>}, an empty one {@code <absent>}.
     */
    @Override
    public String toString() {
        return "DeviceCredential[deviceId=" + deviceId
                + ", token=" + (token.isBlank() ? "<absent>" : "<redacted>") + "]";
    }
}
