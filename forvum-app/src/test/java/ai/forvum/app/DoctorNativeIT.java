package ai.forvum.app;

import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;

/**
 * Native parity for {@code forvum doctor}: re-runs {@link DoctorCommandHealthyTest}'s valid-home assertions
 * against the produced native binary, OUT-OF-PROCESS. Doctor is offline and deterministic (it only reads
 * config files and enumerates the in-image providers — no network, no live LLM), so unlike the live
 * {@code OllamaNativeTurnIT} this carries NO {@code @Tag("live")} and runs in the default native leg: it is
 * a free, real native exercise of the config-validation path, the picocli one-shot dispatch, and
 * {@code Instance<ModelProvider>} discovery in the image.
 *
 * <p>The seeded home reaches the launched binary as {@code -Dforvum.home} via the inherited
 * {@link DoctorCommandHealthyTest.HealthyHomeProfile} config overrides (the integration-test launcher
 * applies the profile's overrides to the artifact); the {@code @TestProfile} is re-declared here so the
 * native subprocess gets it without relying on annotation inheritance.
 */
@QuarkusMainIntegrationTest
@TestProfile(DoctorCommandHealthyTest.HealthyHomeProfile.class)
class DoctorNativeIT extends DoctorCommandHealthyTest {
}
