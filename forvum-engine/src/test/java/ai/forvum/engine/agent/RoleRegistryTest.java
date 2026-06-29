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
