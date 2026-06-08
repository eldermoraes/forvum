package ai.forvum.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.RoleRegistry;
import ai.forvum.engine.context.CurrentIdentity;
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
import java.util.Set;
import java.util.function.Supplier;

/**
 * Security-test layer (ULTRAPLAN section 10): the P2-11 RBAC acceptance for the distinguished {@code cron}
 * role — a cron-fired turn runs under a restricted, read-only scope set, so a tool requiring
 * {@code FS_WRITE} is denied even when the belt allows it. With NO {@code roles/} files present the
 * built-in {@code cron} role applies (so the native CI smoke, which runs with no {@code ~/.forvum/}, still
 * enforces it). Drives the real engine {@link ToolExecutor} + Panache recorder against a real SQLite
 * database (temp {@code $FORVUM_HOME}, Flyway-migrated), binding exactly the scope set
 * {@code CronScheduler.fire} binds ({@code roleRegistry.scopesFor(RoleRegistry.CRON)}).
 *
 * <p>Companion to {@code RoleRestrictedToolDeniedTest} (identity roles). Non-live, default build.
 */
@QuarkusTest
@TestProfile(CronRoleEnforcedTest.CronRoleHomeProfile.class)
class CronRoleEnforcedTest {

    private static final ToolSpec READ = new ToolSpec("cron.read", "read a thing", PermissionScope.FS_READ, "{}");
    private static final ToolSpec WRITE = new ToolSpec("cron.write", "write a thing", PermissionScope.FS_WRITE, "{}");
    // The cron agent's belt grants BOTH, so a denial can only come from the cron role's scope gate.
    private static final List<ToolSpec> BELT = ToolFilter.filter(List.of("cron.read", "cron.write"),
            List.of(READ, WRITE));

    @Inject
    ToolExecutor executor;

    @Inject
    RoleRegistry roleRegistry;

    @Test
    void theBuiltInCronRoleIsReadOnlyAndDeniesAnInBeltWriteTool() {
        Set<PermissionScope> cronScopes = roleRegistry.scopesFor(RoleRegistry.CRON);
        assertEquals(Set.of(PermissionScope.FS_READ), cronScopes, "the built-in cron role is read-only");

        Supplier<String> mustNotRun = () -> {
            throw new AssertionError("a cron turn must not run a tool outside the cron scope set");
        };

        assertThrows(PermissionDeniedException.class, () ->
                ScopedValue.where(CurrentIdentity.CURRENT_EFFECTIVE_SCOPES, cronScopes)
                        .call(() -> executor.execute("cron:brief", new AgentId("main"), BELT,
                                "cron.write", "{}", mustNotRun)));

        // Scope the count to this call's session (M7 convention, CLAUDE.md §14): the @TestProfile DB is
        // shared across methods, so assert only on the rows this method wrote.
        long denied = ToolInvocationEntity.count("sessionId = ?1 and status = ?2 and toolName = ?3",
                "cron:brief", "denied", "cron.write");
        assertEquals(1L, denied, "the cron-role denial must be audited with status='denied'");
    }

    @Test
    void theCronRoleStillAllowsReadOnlyTools() {
        Set<PermissionScope> cronScopes = roleRegistry.scopesFor(RoleRegistry.CRON);

        String result = ScopedValue.where(CurrentIdentity.CURRENT_EFFECTIVE_SCOPES, cronScopes)
                .call(() -> executor.execute("cron:brief-ok", new AgentId("main"), BELT,
                        "cron.read", "{}", () -> "contents"));

        assertEquals("contents", result, "a cron turn may still read (FS_READ is in the cron scope set)");
    }

    /** A throwaway temp home with NO roles/ files, so the built-in cron role is exercised (SQLite + Flyway
     * create the schema). */
    public static class CronRoleHomeProfile implements QuarkusTestProfile {

        static final Path HOME = createHome();

        private static Path createHome() {
            try {
                return Files.createTempDirectory("forvum-cron-role-home");
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
