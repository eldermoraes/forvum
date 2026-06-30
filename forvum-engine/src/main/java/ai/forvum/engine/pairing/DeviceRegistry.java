package ai.forvum.engine.pairing;

import ai.forvum.core.DeviceCredential;
import ai.forvum.engine.config.ConfigurationChangedEvent;
import ai.forvum.engine.config.DeviceReader;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves a device endpoint id to its paired {@link Device} and validates it at the turn entry (P2-4,
 * ULTRAPLAN section 7.2 item 4) — "fixed code, configurable behavior": a device is paired by dropping a
 * {@code $FORVUM_HOME/devices/<id>.json} (id, a pairing token/shared-secret, the reused identity id), no
 * recompile. The cache mirrors {@link ai.forvum.engine.agent.RoleRegistry} / {@code AgentRegistry} (a
 * {@code ConcurrentMap} with file IO kept off the compute lock — no carrier-thread pinning) and evicts on
 * the M4 {@link ConfigurationChangedEvent}.
 *
 * <p><b>Opt-in enforcement (no migration).</b> When {@code devices/} is empty/absent, pairing is disabled
 * and {@link #requirePaired} is a no-op — every channel turn runs, mirroring how P2-11 RBAC is opt-in
 * restriction so an existing install keeps working. Once any {@code devices/<id>.json} exists, only a
 * declared, non-revoked device is paired; an unknown or revoked device is rejected at the turn entry.
 *
 * <p><b>Cron/server/cli exempt.</b> The distinguished built-in {@value #CRON}, {@value #SERVER}, and
 * {@value #CLI} devices are ALWAYS paired (mirroring how P2-11 exempts the {@code cron} role): a
 * background/server operation has no channel device file to present, and the local operator CLI
 * ({@code forvum ask}, channel {@value #CLI}) is the host's inherently-trusted primary surface — device
 * pairing pairs a SECOND device (a phone), it must never lock out the host terminal. So
 * {@link #requirePaired} short-circuits for them.
 *
 * <p>A paired device's {@code identityId} is RECORDED here (for the P2-PAIR-SCOPE #44 CLI/management
 * surface); the memory namespace is shared by the existing {@code IdentityResolver} channel-account
 * mapping, which {@code TurnService} consults to resolve the session identity from
 * {@code (channelId, nativeUserId)} — not by this registry reading {@code device.identityId()}.
 */
@ApplicationScoped
public class DeviceRegistry {

    /** The distinguished always-paired device for cron-fired turns (no channel file). */
    public static final String CRON = "cron";

    /** The distinguished always-paired device for internal/server operations (no channel file). */
    public static final String SERVER = "server";

    /**
     * The distinguished always-paired device for the local operator CLI ({@code forvum ask}). The host
     * terminal is the inherently-trusted primary surface — device pairing pairs a SECOND device, so it must
     * never lock out the host. {@code AskCommand} presents this id as its {@code ChannelMessage.channelId}.
     */
    public static final String CLI = "cli";

    private static final Set<String> EXEMPT = Set.of(CRON, SERVER, CLI);

    @Inject
    DeviceReader reader;

    private final DeviceSpecReader specReader = new DeviceSpecReader();

    /** Negative-caching per device id: {@code present} ⇒ declared, {@code empty} ⇒ no file. */
    private final ConcurrentMap<String, Optional<Device>> cache = new ConcurrentHashMap<>();

    /** Whether any {@code devices/<id>.json} exists; when false, pairing is disabled (opt-in). */
    private volatile Boolean enabled;

    /**
     * Validate the device a turn arrives on, BEFORE the responder runs. A no-op when pairing is disabled
     * (no device files) or the device is the exempt cron/server device or a known-good paired device;
     * throws {@link DeviceNotPairedException} when pairing is enabled and the device is unknown or revoked.
     */
    public void requirePaired(String deviceId) {
        if (EXEMPT.contains(deviceId) || !pairingEnabled()) {
            return; // exempt device, or opt-in pairing not configured — let the turn through
        }
        resolve(deviceId); // declared + non-revoked, or throw
    }

    /**
     * Authenticate the device a turn arrives on, BEFORE the responder runs (#166). Returns the paired
     * {@link Device} to authorize the turn against (the caller intersects its {@code approvedScopes}),
     * or {@link Optional#empty()} when pairing is disabled (opt-in — no device restriction).
     *
     * <ul>
     *   <li>An exempt {@code cron}/{@code server}/{@code cli} device authenticates unconditionally — the
     *       local/trusted surfaces (a presented credential, if any, is ignored).</li>
     *   <li>Pairing disabled (no {@code devices/}) ⇒ {@link Optional#empty()} (P2-4 opt-in, backward
     *       compatible; #170 decides when pairing becomes mandatory).</li>
     *   <li>Otherwise the device must be declared and non-revoked ({@link #resolve}, else
     *       {@link DeviceNotPairedException}).</li>
     *   <li>A PRESENT credential ({@link DeviceCredential#present()}) is bound to the channel — its
     *       {@code deviceId} MUST equal {@code channelId}, so a credential issued for one channel/device
     *       cannot authorize another — and, when the device declares a {@code token}, the presented token
     *       MUST match it in constant time ({@link MessageDigest#isEqual}); a wrong or missing token
     *       throws {@link DeviceAuthenticationException}.</li>
     *   <li>An ABSENT credential keeps the backward-compatible P2-4 paired-by-existence behavior (the
     *       operator/local path).</li>
     * </ul>
     *
     * <p><strong>Secret hygiene (#166).</strong> No token — presented or expected — ever appears in a
     * thrown message; only the device id and the failure kind do.
     */
    public Optional<Device> authenticate(String channelId, DeviceCredential credential) {
        if (EXEMPT.contains(channelId)) {
            return Optional.of(resolve(channelId)); // local/trusted surface — always paired
        }
        if (!pairingEnabled()) {
            return Optional.empty(); // opt-in: no device files ⇒ no device restriction (P2-4)
        }
        Device device = resolve(channelId); // declared + non-revoked, else DeviceNotPairedException (P2-4)
        if (credential.present()) {
            if (!channelId.equals(credential.deviceId())) {
                throw new DeviceAuthenticationException(
                        "Device credential for '" + credential.deviceId() + "' cannot authorize a turn on "
                      + "channel '" + channelId + "' — a credential is bound to one device/channel.");
            }
            if (!device.token().isBlank() && !constantTimeEquals(credential.token(), device.token())) {
                throw new DeviceAuthenticationException(
                        "Device '" + channelId + "' presented an invalid or missing token. Re-pair it with "
                      + "the token from devices/" + channelId + ".json.");
            }
        }
        return Optional.of(device);
    }

    /** Constant-time secret compare (mirrors {@code OperatorCredentialStore}) so a wrong token cannot be timed. */
    private static boolean constantTimeEquals(String presented, String expected) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The paired {@link Device} for {@code deviceId}: the exempt cron/server synthetic device, else the
     * {@code devices/<id>.json} declaration. Throws {@link DeviceNotPairedException} when the device is
     * unknown (no file) or declared-but-{@code revoked}.
     */
    public Device resolve(String deviceId) {
        if (EXEMPT.contains(deviceId)) {
            return new Device(deviceId, "", deviceId, false); // always paired; reuses its own namespace
        }
        Device device = lookup(deviceId).orElseThrow(() -> new DeviceNotPairedException(
                "Device '" + deviceId + "' is not paired: no devices/" + deviceId + ".json. Pair it by "
              + "adding that file (id, token, identityId) before it can drive a turn."));
        if (device.revoked()) {
            throw new DeviceNotPairedException(
                    "Device '" + deviceId + "' is revoked: devices/" + deviceId + ".json has "
                  + "\"revoked\": true. Re-pair it (set revoked false) to restore access.");
        }
        return device;
    }

    /** The {@code Identity} id a paired device reuses — its shared memory namespace (P2-4). */
    public String pairedIdentity(String deviceId) {
        return resolve(deviceId).identityId();
    }

    private Optional<Device> lookup(String deviceId) {
        Optional<Device> cached = cache.get(deviceId);
        if (cached == null) {
            cache.putIfAbsent(deviceId, load(deviceId)); // IO stays off the map lock (idempotent read)
            cached = cache.get(deviceId);
        }
        return cached;
    }

    private Optional<Device> load(String deviceId) {
        return reader.read(deviceId).map(spec -> specReader.parse(deviceId, spec));
    }

    private boolean pairingEnabled() {
        Boolean current = enabled;
        if (current == null) {
            current = !reader.ids().isEmpty();
            enabled = current;
        }
        return current;
    }

    /** Hot reload: evict a device's cached declaration when its {@code devices/<id>.json} changes (M4). */
    void onConfigChange(@Observes ConfigurationChangedEvent event) {
        Path path = event.path();
        if (path.getNameCount() < 1 || !"devices".equals(path.getName(0).toString())) {
            return;
        }
        String fileName = path.getFileName().toString();
        if (!fileName.endsWith(".json")) {
            return; // only device files map to a device id
        }
        cache.remove(fileName.substring(0, fileName.lastIndexOf('.')));
        enabled = null; // a created/deleted device file flips opt-in enablement — recompute lazily
    }
}
