package ai.forvum.app;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@code forvum doctor} over a home whose {@code skills/} carries a schema-declaring skill (#191): doctor
 * parses the skill front-matter and compiles its {@code inputSchema} through the networknt-backed
 * {@code OutputSchemaValidator} (#124) via {@code ConfigDoctor.checkSkills}, reporting a clean bill of health
 * (exit 0). This is the deterministic, OFFLINE driver that proves the {@code SkillReader} parse + JSON-Schema
 * compile RUN inside the binary (not just that it native-COMPILEs, [M20]/[Risk#5]);
 * {@link SkillDoctorNativeIT} re-runs it against the native binary. A live model is never contacted (doctor
 * only reads files); the malformed-skill ERROR branch is covered by a {@code ConfigDoctorTest} unit.
 */
@QuarkusMainTest
@TestProfile(SkillDoctorTest.SkillHomeProfile.class)
class SkillDoctorTest {

    @Test
    @Launch({"doctor"})
    void doctorOnAValidSchemaCarryingSkillReportsHealthyAndExitsZero(LaunchResult result) {
        Assertions.assertEquals(0, result.exitCode(),
                () -> "doctor must exit 0 when the skill's inputSchema is a valid JSON Schema; stderr: "
                        + result.getErrorOutput() + "; stdout: " + result.getOutput());
        Assertions.assertTrue(result.getOutput().contains("No problems found"),
                () -> "doctor must report a clean bill of health; got: " + result.getOutput());
    }

    /** Seeds a valid {@code main} agent plus a schema-declaring skill under {@code skills/}. */
    public static class SkillHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-doctor-skill-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"ollama:qwen3:1.7b\", \"allowedTools\": [] }");
                Path skills = Files.createDirectories(home.resolve("skills"));
                Files.writeString(skills.resolve("greeting.md"),
                        "---\n{ \"description\": \"Greet a person\","
                                + " \"inputSchema\": { \"type\": \"object\", \"required\": [\"who\"],"
                                + " \"properties\": { \"who\": { \"type\": \"string\" } } } }\n---\n"
                                + "Hello {{who}}, welcome!");
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
