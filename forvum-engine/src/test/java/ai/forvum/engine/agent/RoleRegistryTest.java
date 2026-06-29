package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.core.PermissionScope;
import ai.forvum.engine.config.ChangeType;
import ai.forvum.engine.config.ConfigurationChangedEvent;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link RoleRegistry} (P2-11): the role &rarr; scope-set resolver. Built-in roles resolve with no files
 * ({@code default-user} = every scope, {@code cron} = read-only); a {@code roles/<name>.json} defines a
 * new role or overrides a built-in; effective scopes union an identity's roles (empty roles =&gt; the
 * permissive default); an undefined role is an error; a role-file change hot-reloads. Surefire-run
 * (headless library, CLAUDE.md section 4).
 */
@QuarkusTest
@TestProfile(RoleRegistryTest.RoleHomeProfile.class)
class RoleRegistryTest {

    @Inject
    RoleRegistry roles;

    @Inject
    Event<ConfigurationChangedEvent> configChanged;

    @Test
    void builtInDefaultUserGrantsEveryRegisteredScope() {
        assertEquals(EnumSet.allOf(PermissionScope.class), roles.scopesFor(RoleRegistry.DEFAULT_USER));
    }

    @Test
    void builtInCronIsReadOnly() {
        assertEquals(Set.of(PermissionScope.FS_READ), roles.scopesFor(RoleRegistry.CRON));
    }

    @Test
    void builtInAnonymousGrantsNoScopes() {
        assertEquals(Set.of(), roles.scopesFor(RoleRegistry.ANONYMOUS));
    }

    @Test
    void effectiveScopesOfTheAnonymousRoleIsEmptyNotThePermissiveDefault() {
        // [anonymous] is a NON-empty role list, so it unions the (empty) anonymous role — it must NOT
        // collapse to the permissive empty-roles default (#168). This is what stops an unresolved user
        // escalating to every scope by becoming anonymous.
        assertEquals(Set.of(), roles.effectiveScopes(List.of(RoleRegistry.ANONYMOUS)));
    }

    @Test
    void aFileDefinedRoleResolvesToItsScopes() {
        assertEquals(Set.of(PermissionScope.FS_READ), roles.scopesFor("restricted"));
        assertEquals(Set.of(PermissionScope.FS_WRITE), roles.scopesFor("writer"));
    }

    @Test
    void effectiveScopesOfNoRolesIsThePermissiveDefault() {
        assertEquals(EnumSet.allOf(PermissionScope.class), roles.effectiveScopes(List.of()));
        assertEquals(EnumSet.allOf(PermissionScope.class), roles.effectiveScopes(null));
    }

    @Test
    void effectiveScopesUnionsMultipleRoles() {
        assertEquals(Set.of(PermissionScope.FS_READ, PermissionScope.FS_WRITE),
                roles.effectiveScopes(List.of("restricted", "writer")));
    }

    @Test
    void anUndefinedRoleIsAnError() {
        assertThrows(IllegalStateException.class, () -> roles.scopesFor("ghost-role"));
    }

    // --- #167: capScopes — the AGENT-level role cap (caller scopes ∩ the agent roles' union). Unlike
    // --- effectiveScopes (the caller's own roles, empty => permissive default), an empty agent role list
    // --- is NO cap, and the cap can only ever RESTRICT — it never grants a scope the caller lacks (DP-8).

    @Test
    void capScopesWithNoAgentRolesLeavesCallerScopesUnchanged() {
        Set<PermissionScope> caller = EnumSet.of(PermissionScope.FS_READ, PermissionScope.FS_WRITE);
        assertEquals(caller, roles.capScopes(caller, List.of()),
                "an agent that declares no roles imposes no cap — the caller's scopes pass through");
        assertEquals(caller, roles.capScopes(caller, null),
                "a null agent role list is no cap either");
    }

    @Test
    void capScopesIntersectsCallerWithTheAgentRoleUnion() {
        Set<PermissionScope> caller = EnumSet.of(
                PermissionScope.FS_READ, PermissionScope.FS_WRITE, PermissionScope.SHELL_EXEC);
        assertEquals(Set.of(PermissionScope.FS_READ), roles.capScopes(caller, List.of("restricted")),
                "the agent's reader cap restricts a broad caller to FS_READ");
    }

    @Test
    void capScopesUnionsMultipleAgentRolesBeforeIntersecting() {
        Set<PermissionScope> caller = EnumSet.allOf(PermissionScope.class);
        assertEquals(Set.of(PermissionScope.FS_READ, PermissionScope.FS_WRITE),
                roles.capScopes(caller, List.of("restricted", "writer")),
                "the cap is the UNION of the agent's roles, intersected with the caller");
    }

    @Test
    void capScopesCannotGrantAScopeTheCallerLacks() {
        Set<PermissionScope> caller = EnumSet.of(PermissionScope.FS_READ);
        assertEquals(Set.of(), roles.capScopes(caller, List.of("writer")),
                "an agent role is a CAP, never a grant — writer (FS_WRITE) cannot add a scope the caller lacks");
    }

    @Test
    void capScopesWithAnEmptyCallerIsEmpty() {
        assertEquals(Set.of(),
                roles.capScopes(EnumSet.noneOf(PermissionScope.class), List.of("restricted")),
                "an empty caller (the anonymous tail) capped by any agent role stays empty");
    }

    @Test
    void capScopesWithAnUnknownAgentRoleFailsClosed() {
        assertThrows(IllegalStateException.class,
                () -> roles.capScopes(EnumSet.allOf(PermissionScope.class), List.of("ghost-role")),
                "a named-but-undefined agent role is a security-sensitive config error — fail closed, never no-cap");
    }

    @Test
    void capScopesReflectsAHotReloadedRoleFileButNeverMutatesAnAlreadyComputedSnapshot() throws IOException {
        // #167 acceptance #6: a hot reload of a role file affects NEW turns (the cache is evicted), but the
        // capped set already computed for an in-flight turn is an immutable snapshot — the reload can never
        // widen a running turn's authorization (the engine binds this value once at turn entry).
        Path roleFile = RoleHomeProfile.HOME.resolve("roles").resolve("restricted.json");
        Set<PermissionScope> caller = EnumSet.allOf(PermissionScope.class);
        try {
            Set<PermissionScope> snapshot = roles.capScopes(caller, List.of("restricted"));
            assertEquals(Set.of(PermissionScope.FS_READ), snapshot, "the cap before the reload is FS_READ");

            Files.writeString(roleFile, "{\"scopes\":[\"FS_READ\",\"FS_WRITE\"]}");
            configChanged.fire(new ConfigurationChangedEvent(
                    Path.of("roles", "restricted.json"), ChangeType.CREATED));

            assertEquals(Set.of(PermissionScope.FS_READ, PermissionScope.FS_WRITE),
                    roles.capScopes(caller, List.of("restricted")),
                    "a NEW turn's cap reflects the hot-reloaded, widened role file");
            assertEquals(Set.of(PermissionScope.FS_READ), snapshot,
                    "the snapshot computed for an in-flight turn is unchanged — the reload cannot widen it");
        } finally {
            Files.writeString(roleFile, "{ \"scopes\": [\"FS_READ\"] }");
            configChanged.fire(new ConfigurationChangedEvent(
                    Path.of("roles", "restricted.json"), ChangeType.CREATED));
        }
    }

    @Test
    void aRoleFileOverridesABuiltInAndHotReloadEvictsTheCache() throws IOException {
        Path roleFile = RoleHomeProfile.HOME.resolve("roles").resolve("cron.json");
        try {
            assertEquals(Set.of(PermissionScope.FS_READ), roles.scopesFor(RoleRegistry.CRON),
                    "the built-in cron role is read-only before any override");

            Files.writeString(roleFile, "{\"scopes\":[\"FS_READ\",\"FS_WRITE\"]}");
            configChanged.fire(new ConfigurationChangedEvent(
                    Path.of("roles", "cron.json"), ChangeType.CREATED));

            assertEquals(Set.of(PermissionScope.FS_READ, PermissionScope.FS_WRITE),
                    roles.scopesFor(RoleRegistry.CRON),
                    "a roles/cron.json overrides the built-in after the hot-reload eviction");
        } finally {
            // Restore the shared static seed home: drop the override + evict so the built-in returns.
            Files.deleteIfExists(roleFile);
            configChanged.fire(new ConfigurationChangedEvent(
                    Path.of("roles", "cron.json"), ChangeType.DELETED));
        }
    }

    /** Seeds {@code roles/restricted.json} (FS_READ) and {@code roles/writer.json} (FS_WRITE). */
    public static class RoleHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-role-home");
                Path roles = Files.createDirectories(home.resolve("roles"));
                Files.writeString(roles.resolve("restricted.json"), "{ \"scopes\": [\"FS_READ\"] }");
                Files.writeString(roles.resolve("writer.json"), "{ \"scopes\": [\"FS_WRITE\"] }");
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
