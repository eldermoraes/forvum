package ai.forvum.app;

import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;

/**
 * Native parity (#124): re-runs {@link OutputSchemaDoctorTest}'s valid-{@code outputSchema} assertions
 * against the produced native binary, OUT-OF-PROCESS. This is the proof that the {@code com.networknt}
 * JSON-Schema engine RUNS inside a GraalVM native image — its factory init, the bundled draft-2020-12
 * meta-schema RESOURCE load (covered by the library's own {@code resource-config.json}), and the schema
 * compile — beyond the binary merely native-COMPILING + booting ([M20]/[Risk#5]). {@code doctor} is offline
 * and deterministic (it only reads files, no live LLM), so like {@code DoctorNativeIT} this carries NO
 * {@code @Tag("live")} and runs in the DEFAULT native leg.
 *
 * <p>The seeded home reaches the launched binary as {@code -Dforvum.home} via the inherited
 * {@link OutputSchemaDoctorTest.SchemaHomeProfile} config overrides (the integration-test launcher applies
 * them to the artifact); the {@code @TestProfile} is re-declared so the native subprocess gets it without
 * annotation inheritance.
 */
@QuarkusMainIntegrationTest
@TestProfile(OutputSchemaDoctorTest.SchemaHomeProfile.class)
class OutputSchemaDoctorNativeIT extends OutputSchemaDoctorTest {
}
