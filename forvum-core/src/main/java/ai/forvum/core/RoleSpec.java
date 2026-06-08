package ai.forvum.core;

import java.util.Set;

/**
 * A named authorization role: the set of {@link PermissionScope}s granted to an identity that declares
 * it (ULTRAPLAN section 4.3.4 / section 7.2 item 11, P2-11). The role &rarr; scope-set mapping sits
 * <em>above</em> the {@link PermissionScope} enum — the enum stays a flat list of capabilities; this
 * record composes them into a grant.
 *
 * <p>Distinct from {@link Role} (the conversation message role, a {@code messages.role} mirror). Role
 * policy is "fixed code, configurable behavior": the engine ships built-in roles (a permissive
 * {@code default-user} and a restricted {@code cron}) and overlays {@code $FORVUM_HOME/roles/<name>.json}
 * over them. The engine's {@code ToolExecutor} denies a tool whose {@link ToolSpec#requiredScope()} is
 * outside the caller's effective scopes (the union of its roles' {@code scopes}).
 *
 * <p>{@code scopes} is defensively copied to an immutable set by the canonical constructor.
 */
public record RoleSpec(String name, Set<PermissionScope> scopes) {
    public RoleSpec {
        if (name == null || name.isBlank() || !name.strip().equals(name)) {
            throw new IllegalStateException(
                "RoleSpec name must be a non-blank token without leading/trailing whitespace. "
              + "Got: '" + name + "'. Check the roles/<name>.json filename.");
        }
        if (scopes == null) {
            throw new IllegalStateException(
                "RoleSpec '" + name + "' scopes must be non-null (use an empty set to grant nothing). "
              + "Check the 'scopes' array in roles/" + name + ".json.");
        }
        for (PermissionScope scope : scopes) {
            if (scope == null) {
                throw new IllegalStateException(
                    "RoleSpec '" + name + "' scopes must not contain null. Check the 'scopes' array in "
                  + "roles/" + name + ".json.");
            }
        }
        scopes = Set.copyOf(scopes);
    }
}
