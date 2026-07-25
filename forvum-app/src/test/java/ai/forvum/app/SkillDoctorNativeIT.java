package ai.forvum.app;

import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;

/**
 * Native parity (#124/#191): re-runs {@link SkillDoctorTest}'s valid-skill assertion against the produced
 * native binary, OUT-OF-PROCESS. This is the proof that the {@code SkillReader} front-matter parse + the
 * {@code com.networknt} JSON-Schema compile of a skill's {@code inputSchema} RUN inside a GraalVM native
 * image, beyond the binary merely native-COMPILING + booting ([M20]/[Risk#5]). {@code doctor} is offline
 * and deterministic (it only reads files, no live LLM), so like {@code OutputSchemaDoctorNativeIT} this
 * carries NO {@code @Tag("live")} and runs in the DEFAULT native leg. The seeded home reaches the launched
 * binary as {@code -Dforvum.home} via the inherited {@link SkillDoctorTest.SkillHomeProfile}, re-declared
 * so the native subprocess gets it without annotation inheritance.
 */
@QuarkusMainIntegrationTest
@TestProfile(SkillDoctorTest.SkillHomeProfile.class)
class SkillDoctorNativeIT extends SkillDoctorTest {
}
