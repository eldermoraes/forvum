package ai.forvum.engine.pairing;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@link DeviceRegistry} opt-in semantics (P2-4): with NO {@code devices/} declarations, pairing is
 * disabled and {@link DeviceRegistry#requirePaired} is a no-op for any device — an existing install with
 * no device files keeps working with no migration (mirrors P2-11 RBAC's opt-in restriction). A separate
 * profile/home from {@link DeviceRegistryTest} because the {@code @ApplicationScoped} enablement flag is
 * computed once per app instance. Surefire-run (headless library, CLAUDE.md section 4).
 */
@QuarkusTest
@TestProfile(DeviceRegistryOptInTest.NoDevicesHomeProfile.class)
class DeviceRegistryOptInTest {

    @Inject
    DeviceRegistry devices;

    @Test
    void pairingIsDisabledWhenNoDevicesAreDeclared() {
        // No devices/ folder at all: any channel device passes the guard (opt-in, backward compatible).
        devices.requirePaired("web");
        devices.requirePaired("tui");
        devices.requirePaired("anything-at-all");
    }

    /** A home with agents but NO devices/ folder — pairing must stay disabled. */
    public static class NoDevicesHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-no-devices-home");
                Files.createDirectories(home.resolve("agents"));
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
