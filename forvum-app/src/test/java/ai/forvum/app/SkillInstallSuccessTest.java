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
 * {@code forvum skill install <url>} SUCCESS path end-to-end through the CLI (P2-7 #32): a {@code file://}
 * URL to a skill {@code .md} (front-matter {@code name} = "summarize") is downloaded, validated through the
 * real {@code SkillReader}, and written into {@code ~/.forvum/skills/}. Hermetic + offline: the "remote"
 * skill is a {@code @TempDir}-style temp file served as {@code file://}, and {@code forvum.home} is an
 * isolated temp dir. Runs under Surefire (the forvum-app {@code @QuarkusMainTest} family, CLAUDE.md §4).
 */
@QuarkusMainTest
@TestProfile(SkillInstallSuccessTest.HomeProfile.class)
class SkillInstallSuccessTest {

    @Test
    void installsTheSkillAndWritesItIntoSkills(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("skill", "install", HomeProfile.SKILL_URL);

        Assertions.assertEquals(0, result.exitCode(),
                () -> "a valid skill URL must exit 0; stderr: " + result.getErrorOutput());
        Assertions.assertTrue(result.getOutput().contains("Installed skill 'summarize'"),
                () -> "must print the installed skill id; got: " + result.getOutput());
        Path installed = HomeProfile.FORVUM_HOME.resolve("skills").resolve("summarize.md");
        Assertions.assertTrue(Files.isRegularFile(installed),
                () -> "the skill must be written to ~/.forvum/skills/summarize.md");
    }

    public static class HomeProfile implements QuarkusTestProfile {

        static final Path FORVUM_HOME = createTempDir("forvum-skill-install-home");
        static final String SKILL_URL = seedSkill();

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

        /** Stage a valid skill .md OUTSIDE forvum.home (the "remote") and return its file:// URL. */
        private static String seedSkill() {
            try {
                Path remote = createTempDir("forvum-skill-install-remote").resolve("summarize.md");
                Files.writeString(remote, """
                        ---
                        { "name": "summarize", "description": "Summarize text",
                          "inputSchema": { "type": "object", "required": ["text"],
                            "properties": { "text": { "type": "string" } } } }
                        ---
                        Summarize the following: {{text}}
                        """);
                return remote.toUri().toString();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
