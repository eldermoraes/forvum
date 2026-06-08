package ai.forvum.engine.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
