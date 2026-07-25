package ai.forvum.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.engine.skills.SkillToolProvider;

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
 * Security-test layer (ULTRAPLAN section 10): the assembled app's engine-resident skill surface (#191)
 * refuses a path-traversal skill name — a model-supplied {@code name} cannot escape {@code skills/} to read
 * an arbitrary file. Drives the REAL wired {@link SkillToolProvider} (CDI-discovered on the app classpath,
 * the M12 combined-classpath value) over a temp {@code $FORVUM_HOME} that plants a secret OUTSIDE
 * {@code skills/}; the id-format guard fires before any filesystem access, so the secret never leaks.
 * Companion to {@code PathTraversalDeniedTest} (filesystem {@code WorkspaceRoot} confinement). Non-live,
 * so it runs in the default build; named {@code *Test} (an in-JVM {@code @QuarkusTest}, never {@code *IT}).
 */
@QuarkusTest
@TestProfile(SkillPathTraversalDeniedTest.SkillHomeProfile.class)
class SkillPathTraversalDeniedTest {

    static final String SECRET = "TOP-SECRET-AGENT-PROMPT";

    @Inject
    SkillToolProvider skills;

    @Test
    void aTraversalSkillNameIsRefusedWithoutReadingOutsideSkills() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                skills.invoke("skill.invoke", Map.of("name", "../agents/main")));

        assertFalse(thrown.getMessage().contains(SECRET),
                "the refusal must not read or echo the traversal target's content");
    }

    @Test
    void aLegitimateSkillIdStillResolves() {
        String result = skills.invoke("skill.invoke", Map.of("name", "greeting", "args", Map.of("who", "Ada")));
        assertEquals("Hello Ada", result, "a valid in-directory skill id still expands normally");
    }

    /** Seeds a real {@code skills/greeting.md} plus a secret {@code agents/main.md} a traversal would target. */
    public static class SkillHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-skill-traversal-home");
                Files.writeString(Files.createDirectories(home.resolve("agents")).resolve("main.md"), SECRET);
                Files.writeString(Files.createDirectories(home.resolve("skills")).resolve("greeting.md"),
                        "Hello {{who}}");
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
