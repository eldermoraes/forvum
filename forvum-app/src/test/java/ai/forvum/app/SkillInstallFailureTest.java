package ai.forvum.app;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@code forvum skill install <url>} FAILURE paths (P2-7 #32): a malformed skill (bad front-matter) and an
 * unsupported URL scheme both exit non-zero with a {@code "Skill install failed"} stderr line and write
 * nothing — the validate-through-the-real-reader gate rejects a bad skill before it lands. Hermetic.
 */
@QuarkusMainTest
@TestProfile(SkillInstallFailureTest.HomeProfile.class)
class SkillInstallFailureTest {

    @Test
    void aMalformedSkillIsRejectedAndNothingIsWritten(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("skill", "install", HomeProfile.MALFORMED_URL);

        Assertions.assertEquals(1, result.exitCode(),
                () -> "a malformed skill must exit 1; out: " + result.getOutput());
        Assertions.assertTrue(result.getErrorOutput().contains("Skill install failed"),
                () -> "must print the failure on stderr; got: " + result.getErrorOutput());
        Assertions.assertTrue(isSkillsDirEmpty(),
                "a rejected skill must NOT be written into ~/.forvum/skills/");
    }

    @Test
    void anUnsupportedUrlSchemeIsRejected(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("skill", "install", "ftp://example.org/skill.md");

        Assertions.assertEquals(1, result.exitCode());
        Assertions.assertTrue(result.getErrorOutput().contains("Skill install failed"));
    }

    private static boolean isSkillsDirEmpty() {
        Path skills = HomeProfile.FORVUM_HOME.resolve("skills");
        if (!Files.isDirectory(skills)) {
            return true;
        }
        try (var entries = Files.list(skills)) {
            return entries.findAny().isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static class HomeProfile implements QuarkusTestProfile {

        static final Path FORVUM_HOME = createTempDir("forvum-skill-fail-home");
        static final String MALFORMED_URL = seedMalformedSkill();

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", FORVUM_HOME.toString());
        }

        private static Path createTempDir(String prefix) {
            try {
                return Files.createTempDirectory(prefix);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static String seedMalformedSkill() {
            try {
                Path remote = createTempDir("forvum-skill-fail-remote").resolve("broken.md");
                // Front-matter opened but the JSON is invalid → rejected on read.
                Files.writeString(remote, "---\n{ \"name\": not-json }\n---\nbody\n");
                return remote.toUri().toString();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
