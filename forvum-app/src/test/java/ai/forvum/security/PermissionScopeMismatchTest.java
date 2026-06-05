package ai.forvum.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.persistence.ToolInvocationEntity;
import ai.forvum.engine.tools.PermissionDeniedException;
import ai.forvum.engine.tools.ToolExecutor;
import ai.forvum.engine.tools.ToolFilter;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Security-test layer (ULTRAPLAN section 10): a {@code PermissionScope} mismatch is denied <em>and</em>
 * audited. Drives the real engine {@link ToolExecutor} + Panache recorder against a real SQLite database
 * (temp {@code $FORVUM_HOME}, Flyway-migrated): an agent whose belt grants only {@code a.read} attempts
 * {@code a.write} → refused with {@link PermissionDeniedException} and a {@code tool_invocations} row
 * with {@code status = 'denied'}. This is the M13 Verify exercised end-to-end through the audit path; the
 * executor's branch logic is unit-covered in the engine's {@code ToolExecutorTest}.
 *
 * <p>The first test under {@code forvum-app/.../security/}; non-live, so it runs in the default build.
 */
@QuarkusTest
@TestProfile(PermissionScopeMismatchTest.SecurityHomeProfile.class)
class PermissionScopeMismatchTest {

    @Inject
    ToolExecutor executor;

    @Test
    void aToolOutsideTheAgentBeltIsDeniedAndAudited() {
        ToolSpec read = new ToolSpec("a.read", "read a thing", PermissionScope.FS_READ, "{}");
        ToolSpec write = new ToolSpec("a.write", "write a thing", PermissionScope.FS_WRITE, "{}");
        // The agent's allowedTools grant only a.read; the belt is the glob intersection of the registry.
        List<ToolSpec> belt = ToolFilter.filter(List.of("a.read"), List.of(read, write));

        Supplier<String> mustNotRun = () -> {
            throw new AssertionError("a denied tool must never run");
        };

        assertThrows(PermissionDeniedException.class, () -> executor.execute(
                "sess-sec", new AgentId("attacker"), belt, "a.write", "{}", mustNotRun));

        long denied = ToolInvocationEntity.count("status = ?1 and toolName = ?2", "denied", "a.write");
        assertEquals(1L, denied,
                "the denied attempt must be audited to tool_invocations with status='denied'");
    }

    /** Points {@code $FORVUM_HOME} at a throwaway temp dir so SQLite + Flyway create the schema. */
    public static class SecurityHomeProfile implements QuarkusTestProfile {

        static final Path HOME = createHome();

        private static Path createHome() {
            try {
                return Files.createTempDirectory("forvum-security-home");
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
