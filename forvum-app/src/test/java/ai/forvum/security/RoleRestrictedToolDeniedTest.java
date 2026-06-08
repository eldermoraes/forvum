package ai.forvum.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.IdentityResolver;
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
 * Security-test layer (ULTRAPLAN section 10): the P2-11 RBAC acceptance — a role-restricted identity is
 * denied a tool outside its role's scope set <em>even though the belt allows it</em>, and the denial is
 * audited. Drives the real engine {@link ToolExecutor} + Panache recorder against a real SQLite database
 * (temp {@code $FORVUM_HOME}, Flyway-migrated): the {@code restricted} identity declares the {@code reader}
 * role (granting only {@code FS_READ}); a belt containing both {@code rbac.read} and {@code rbac.write}
 * (so belt membership is NOT the cause) attempts {@code rbac.write} (which requires {@code FS_WRITE}) under
 * the identity's bound effective scopes → refused with {@link PermissionDeniedException} and a
 * {@code tool_invocations} row {@code status = 'denied'}. The control: an identity that declares no roles
 * gets the permissive default and runs the same tool.
 *
 * <p>Companion to {@code PermissionScopeMismatchTest} (M13 belt denial), {@code CronRoleEnforcedTest}
 * (the cron role), {@code PathTraversalDeniedTest}, and {@code SpawnBoundaryOverrideRejectedTest}.
 * Non-live, so it runs in the default build.
 */
@QuarkusTest
@TestProfile(RoleRestrictedToolDeniedTest.RbacHomeProfile.class)
class RoleRestrictedToolDeniedTest {

    private static final ToolSpec READ = new ToolSpec("rbac.read", "read a thing", PermissionScope.FS_READ, "{}");
    private static final ToolSpec WRITE = new ToolSpec("rbac.write", "write a thing", PermissionScope.FS_WRITE, "{}");
    // The belt grants BOTH tools (glob match), so a denial can only come from the orthogonal scope gate.
    private static final List<ToolSpec> BELT = ToolFilter.filter(List.of("rbac.read", "rbac.write"),
            List.of(READ, WRITE));

    @Inject
    ToolExecutor executor;

    @Inject
    RoleRegistry roleRegistry;

    @Inject
    IdentityResolver identities;

    @Test
    void aRoleRestrictedIdentityIsDeniedAnInBeltToolOutsideItsRoleScopes() {
        // restricted -> role 'reader' -> {FS_READ}. rbac.write requires FS_WRITE -> denied + audited.
        Set<PermissionScope> effective = roleRegistry.effectiveScopes(identities.rolesFor("restricted"));
        assertEquals(Set.of(PermissionScope.FS_READ), effective, "the reader role grants only FS_READ");

        Supplier<String> mustNotRun = () -> {
            throw new AssertionError("an out-of-scope tool must never run");
        };

        assertThrows(PermissionDeniedException.class, () ->
                ScopedValue.where(CurrentIdentity.CURRENT_EFFECTIVE_SCOPES, effective)
                        .call(() -> executor.execute("sess-rbac", new AgentId("main"), BELT,
                                "rbac.write", "{}", mustNotRun)));

        // Scope the count to this call's session (M7 convention, CLAUDE.md §14): the @TestProfile DB is
        // shared across methods, so assert only on the rows this method wrote.
        long denied = ToolInvocationEntity.count("sessionId = ?1 and status = ?2 and toolName = ?3",
                "sess-rbac", "denied", "rbac.write");
        assertEquals(1L, denied,
                "the role-restricted denial must be audited to tool_invocations with status='denied'");
    }

    @Test
    void anIdentityWithoutRolesGetsThePermissiveDefaultAndRunsTheSameTool() {
        // openuser declares no roles -> default-user -> every scope -> the same in-belt tool runs OK.
        Set<PermissionScope> effective = roleRegistry.effectiveScopes(identities.rolesFor("openuser"));

        String result = ScopedValue.where(CurrentIdentity.CURRENT_EFFECTIVE_SCOPES, effective)
                .call(() -> executor.execute("sess-rbac-ok", new AgentId("main"), BELT,
                        "rbac.write", "{}", () -> "written"));

        assertEquals("written", result, "an unrestricted identity is gated only by the belt, like before");
    }

    /** Seeds a {@code reader} role (FS_READ) + a {@code restricted} identity declaring it, plus an
     * {@code openuser} identity with no roles, into a throwaway temp home (SQLite + Flyway create the schema). */
    public static class RbacHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-rbac-home");
                Path roles = Files.createDirectories(home.resolve("roles"));
                Files.writeString(roles.resolve("reader.json"), "{ \"scopes\": [\"FS_READ\"] }");
                Path identities = Files.createDirectories(home.resolve("identities"));
                Files.writeString(identities.resolve("restricted.json"),
                        "{ \"displayName\": \"Restricted\", \"channelAccounts\": { \"web\": \"r1\" }, "
                      + "\"roles\": [\"reader\"] }");
                Files.writeString(identities.resolve("openuser.json"),
                        "{ \"displayName\": \"Open User\", \"channelAccounts\": { \"web\": \"o1\" } }");
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
