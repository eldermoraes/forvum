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
 * {@code forvum doctor} over a home whose {@code main} agent declares a VALID {@code outputSchema} (P2-12):
 * doctor compiles the schema through the networknt-backed {@code OutputSchemaValidator} (#124) and reports a
 * clean bill of health (exit 0). This is the deterministic, OFFLINE driver that proves the JSON-Schema
 * library RUNS — factory init + the bundled draft-2020-12 meta-schema resource load + schema compile — not
 * just that it native-COMPILES ([M20]/[Risk#5]); {@link OutputSchemaDoctorNativeIT} re-runs this exact case
 * against the native binary. A live model is never contacted (doctor only reads files), so unlike a turn it
 * needs no provider in the image. The malformed-schema ERROR branch is covered by a {@code ConfigDoctor}
 * unit test (no boot needed); this asserts the happy path end-to-end through the picocli one-shot.
 */
@QuarkusMainTest
@TestProfile(OutputSchemaDoctorTest.SchemaHomeProfile.class)
class OutputSchemaDoctorTest {

    @Test
    @Launch({"doctor"})
    void doctorOnAValidOutputSchemaReportsHealthyAndExitsZero(LaunchResult result) {
        Assertions.assertEquals(0, result.exitCode(),
                () -> "doctor must exit 0 when the outputSchema is a valid JSON Schema; stderr: "
                        + result.getErrorOutput() + "; stdout: " + result.getOutput());
        Assertions.assertTrue(result.getOutput().contains("No problems found"),
                () -> "doctor must report a clean bill of health; got: " + result.getOutput());
    }

    /** Seeds a {@code main} agent pinned to a real provider and declaring a valid object {@code outputSchema}. */
    public static class SchemaHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-doctor-schema-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"ollama:qwen3:1.7b\", \"allowedTools\": [],"
                                + " \"outputSchema\": { \"type\": \"object\", \"required\": [\"answer\"],"
                                + " \"properties\": { \"answer\": { \"type\": \"string\" },"
                                + " \"score\": { \"type\": \"integer\", \"minimum\": 0, \"maximum\": 10 },"
                                + " \"status\": { \"enum\": [\"ok\", \"error\"] } } } }");
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
