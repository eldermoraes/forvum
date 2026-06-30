package ai.forvum.engine.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.DeviceCredential;
import ai.forvum.engine.config.ChangeType;
import ai.forvum.engine.config.ConfigurationChangedEvent;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@link DeviceRegistry} (P2-4): the device endpoint &rarr; paired {@link Device} resolver + the turn-entry
 * guard. A {@code devices/<id>.json} pairs a device to an identity (it shares that identity's memory
 * namespace); an unknown or revoked device is rejected; the distinguished {@code cron}/{@code server}
 * devices are always paired (exempt); a new device file hot-reloads. Surefire-run (headless library,
 * CLAUDE.md section 4).
 */
@QuarkusTest
@TestProfile(DeviceRegistryTest.DeviceHomeProfile.class)
class DeviceRegistryTest {

    @Inject
    DeviceRegistry devices;

    @Inject
    Event<ConfigurationChangedEvent> configChanged;

    @Test
    void aPairedDeviceResolvesAndSharesItsIdentityNamespace() {
        Device device = devices.resolve("phone");
        assertEquals("phone", device.id());
        assertEquals("alice", device.identityId(), "the paired device reuses alice's identity + memory");
        assertEquals("alice", devices.pairedIdentity("phone"));
        assertFalse(device.revoked());
    }

    @Test
    void requirePairedIsACheapNoOpForAKnownGoodDevice() {
        devices.requirePaired("phone"); // declared + non-revoked — must not throw
    }

    @Test
    void anUnknownDeviceIsRejected() {
        assertThrows(DeviceNotPairedException.class, () -> devices.resolve("ghost"));
        assertThrows(DeviceNotPairedException.class, () -> devices.requirePaired("ghost"));
    }

    @Test
    void aRevokedDeviceIsRejected() {
        assertThrows(DeviceNotPairedException.class, () -> devices.resolve("oldphone"));
        assertThrows(DeviceNotPairedException.class, () -> devices.requirePaired("oldphone"));
    }

    @Test
    void cronAndServerDevicesAreAlwaysPaired() {
        devices.requirePaired(DeviceRegistry.CRON);   // exempt — must not throw even when not declared
        devices.requirePaired(DeviceRegistry.SERVER);
        assertEquals(DeviceRegistry.CRON, devices.resolve(DeviceRegistry.CRON).identityId());
        assertEquals(DeviceRegistry.SERVER, devices.resolve(DeviceRegistry.SERVER).identityId());
    }

    @Test
    void aNewDeviceFileIsPickedUpOnHotReload() throws IOException {
        Path tablet = DeviceHomeProfile.HOME.resolve("devices").resolve("tablet.json");
        try {
            assertThrows(DeviceNotPairedException.class, () -> devices.resolve("tablet"),
                    "tablet is unknown before its file is dropped");

            Files.writeString(tablet, "{ \"token\": \"t-2\", \"identityId\": \"alice\" }");
            configChanged.fire(new ConfigurationChangedEvent(
                    Path.of("devices", "tablet.json"), ChangeType.CREATED));

            assertEquals("alice", devices.pairedIdentity("tablet"),
                    "a devices/tablet.json is picked up after the hot-reload eviction");
        } finally {
            Files.deleteIfExists(tablet);
            configChanged.fire(new ConfigurationChangedEvent(
                    Path.of("devices", "tablet.json"), ChangeType.DELETED));
        }
    }

    // ---- #166 device-token authentication ----

    @Test
    void authenticateAcceptsACorrectTokenForAnActiveDevice() {
        Device device = devices.authenticate("phone", new DeviceCredential("phone", "t-1")).orElseThrow();
        assertEquals("alice", device.identityId(), "a valid token authenticates the device");
    }

    @Test
    void authenticateRejectsAWrongToken() {
        assertThrows(DeviceAuthenticationException.class,
                () -> devices.authenticate("phone", new DeviceCredential("phone", "WRONG-secret")));
    }

    @Test
    void authenticateRejectsAMissingTokenWhenTheDeviceRequiresOne() {
        // A present credential with an empty token, against a device that declares one, is a missing-token failure.
        assertThrows(DeviceAuthenticationException.class,
                () -> devices.authenticate("phone", new DeviceCredential("phone", "")));
    }

    @Test
    void authenticateRejectsACrossChannelCredential() {
        // A credential claiming device 'web' must not authorize a turn arriving on channel 'phone'.
        assertThrows(DeviceAuthenticationException.class,
                () -> devices.authenticate("phone", new DeviceCredential("web", "t-1")));
    }

    @Test
    void authenticateRejectsARevokedDeviceEvenWithItsCorrectToken() {
        // Revocation is enforced before the token — a revoked device is rejected even presenting the right secret.
        assertThrows(DeviceNotPairedException.class,
                () -> devices.authenticate("oldphone", new DeviceCredential("oldphone", "t-0")));
    }

    @Test
    void authenticateRejectsAnUnknownDevice() {
        assertThrows(DeviceNotPairedException.class,
                () -> devices.authenticate("ghost", new DeviceCredential("ghost", "x")));
    }

    @Test
    void anAbsentCredentialKeepsTheBackwardCompatiblePairedByExistencePath() {
        // P2-4: with no per-connection credential (operator/local/legacy) a declared, non-revoked device
        // still authorizes by existence — the token is not required on the ABSENT path (#170 hardens this).
        Device device = devices.authenticate("phone", DeviceCredential.ABSENT).orElseThrow();
        assertEquals("alice", device.identityId());
    }

    @Test
    void anAbsentCredentialOnAnUnknownDeviceStillRejects() {
        assertThrows(DeviceNotPairedException.class,
                () -> devices.authenticate("ghost", DeviceCredential.ABSENT));
    }

    @Test
    void aTokenlessDeviceIsPairedByExistenceAndIgnoresAPresentedToken() {
        // A device file that declares no token opts out of #166 token auth (paired by existence).
        assertTrue(devices.authenticate("kiosk", DeviceCredential.ABSENT).isPresent());
        assertTrue(devices.authenticate("kiosk", new DeviceCredential("kiosk", "anything")).isPresent(),
                "a tokenless device declares no secret to check — it pairs by existence");
    }

    @Test
    void exemptDevicesAuthenticateRegardlessOfCredential() {
        assertTrue(devices.authenticate(DeviceRegistry.CRON, DeviceCredential.ABSENT).isPresent());
        assertTrue(
                devices.authenticate(DeviceRegistry.CLI, new DeviceCredential(DeviceRegistry.CLI, "x")).isPresent(),
                "the local operator CLI is exempt even if a credential is somehow presented");
    }

    /** Seeds a paired {@code phone} (identity alice) and a {@code revoked} {@code oldphone}. */
    public static class DeviceHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-device-home");
                Path dir = Files.createDirectories(home.resolve("devices"));
                Files.writeString(dir.resolve("phone.json"),
                        "{ \"token\": \"t-1\", \"identityId\": \"alice\" }");
                Files.writeString(dir.resolve("oldphone.json"),
                        "{ \"token\": \"t-0\", \"identityId\": \"alice\", \"revoked\": true }");
                // A tokenless device: opts out of #166 token auth, paired by existence (legacy/backward-compat).
                Files.writeString(dir.resolve("kiosk.json"),
                        "{ \"identityId\": \"alice\" }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", HOME.toString());
        }
    }
}
