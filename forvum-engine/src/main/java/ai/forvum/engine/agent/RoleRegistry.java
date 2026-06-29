package ai.forvum.engine.agent;

import ai.forvum.core.PermissionScope;
import ai.forvum.engine.config.ConfigurationChangedEvent;
import ai.forvum.engine.config.RoleReader;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves an authorization-role name to its granted {@link PermissionScope} set (P2-11, ULTRAPLAN
 * section 4.3.4) — "fixed code, configurable behavior": the engine ships built-in roles in code, and a
 * {@code $FORVUM_HOME/roles/<name>.json} overrides a built-in or defines a new role with no recompile.
 * The cache mirrors {@link AgentRegistry} (a {@code ConcurrentMap} with file IO kept off the compute
 * lock — no carrier-thread pinning) and evicts on the M4 {@link ConfigurationChangedEvent}.
 *
 * <p>Built-ins: {@value #DEFAULT_USER} grants every registered scope — the permissive default for a
 * RESOLVED identity that declares no roles, mirroring OpenClaw's "no owner allowlist =&gt; everyone owner"
 * so RBAC is opt-in restriction and existing identities keep working with no migration. {@value #CRON}
 * grants a read-only subset — the distinguished restricted role bound for cron-fired turns.
 * {@value #ANONYMOUS} grants NO scopes — the deliberately restricted role bound for an UNRESOLVED session
 * with no agent fallback (#168), so an unmapped user never escalates to the permissive default. All three
 * are overridable by a same-named role file.
 */
@ApplicationScoped
public class RoleRegistry {

    /** The permissive built-in role for an identity that declares no roles: every registered scope. */
    public static final String DEFAULT_USER = "default-user";

    /** The distinguished restricted built-in role bound for cron-fired turns: read-only. */
    public static final String CRON = "cron";

    /** The deliberately restricted built-in role for an unresolved/anonymous session (#168): no scopes. */
    public static final String ANONYMOUS = "anonymous";

    private static final Map<String, Set<PermissionScope>> BUILT_INS = Map.of(
            DEFAULT_USER, EnumSet.allOf(PermissionScope.class),
            CRON, EnumSet.of(PermissionScope.FS_READ),
            ANONYMOUS, EnumSet.noneOf(PermissionScope.class));

    /**
     * The built-in role names. Exposed so a pure validator ({@code ConfigDoctor}, #167) can decide whether
     * an agent's declared role resolves — against the SAME source of truth as {@link #load}, with no
     * drifting second list of built-ins (a role resolves iff it is here or a {@code roles/<name>.json}).
     */
    public static final Set<String> BUILT_IN_ROLE_NAMES = Set.copyOf(BUILT_INS.keySet());

    @Inject
    RoleReader reader;

    private final RoleSpecReader specReader = new RoleSpecReader();
    private final ConcurrentMap<String, Set<PermissionScope>> cache = new ConcurrentHashMap<>();

    /**
     * The scopes granted by {@code roleName}: a {@code roles/<name>.json} override if present, else the
     * built-in default, else an {@link IllegalStateException} (an identity declared an undefined role).
     */
    public Set<PermissionScope> scopesFor(String roleName) {
        Set<PermissionScope> cached = cache.get(roleName);
        if (cached == null) {
            cache.putIfAbsent(roleName, load(roleName)); // load() is idempotent; IO stays off the map lock
            cached = cache.get(roleName);
        }
        return cached;
    }

    /**
     * The caller's effective scopes: the union of {@code roleNames}' scope-sets, or — when an identity
     * declares no roles — the permissive {@link #DEFAULT_USER} built-in (RBAC is opt-in restriction).
     */
    public Set<PermissionScope> effectiveScopes(List<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return scopesFor(DEFAULT_USER);
        }
        return unionOf(roleNames);
    }

    /** The union of {@code roleNames}' scope-sets in a fresh mutable {@link EnumSet}; throws on an unknown role. */
    private Set<PermissionScope> unionOf(List<String> roleNames) {
        Set<PermissionScope> union = EnumSet.noneOf(PermissionScope.class);
        for (String roleName : roleNames) {
            union.addAll(scopesFor(roleName));
        }
        return union;
    }

    /**
     * Apply an agent's role CAP to the caller's scopes (#167, ULTRAPLAN section 4.3.4 / DR-8 DP-8): the
     * intersection of {@code callerScopes} with the union of the agent roles' scope-sets. An absent/empty
     * agent role list is NO cap — {@code callerScopes} pass through unchanged — because {@code roles} is
     * opt-in restriction, never a grant: the cap can only ever RESTRICT, never add a scope the caller
     * lacks. A named-but-undefined agent role FAILS CLOSED via {@link #scopesFor}'s
     * {@link IllegalStateException} (a security-sensitive config error, NOT silently "no cap"). This is
     * deliberately distinct from {@link #effectiveScopes}, whose empty-list branch is the permissive
     * {@link #DEFAULT_USER} (the caller's own roles); reusing it for the cap would WRONGLY widen an
     * uncapped agent back to every scope.
     */
    public Set<PermissionScope> capScopes(Set<PermissionScope> callerScopes, List<String> agentRoleNames) {
        if (agentRoleNames == null || agentRoleNames.isEmpty()) {
            return callerScopes; // no agent-level cap — the caller's scopes already govern
        }
        Set<PermissionScope> cap = unionOf(agentRoleNames); // throws on an unknown role -> fail closed (never no-cap)
        cap.retainAll(callerScopes); // intersect in place: caller ∩ cap (cap is a fresh mutable EnumSet)
        return cap;
    }

    private Set<PermissionScope> load(String roleName) {
        JsonNode spec = reader.read(roleName).orElse(null);
        if (spec != null) {
            return specReader.parse(roleName, spec).scopes(); // a role file overrides a built-in
        }
        Set<PermissionScope> builtIn = BUILT_INS.get(roleName);
        if (builtIn != null) {
            return Set.copyOf(builtIn);
        }
        throw new IllegalStateException(
                "Unknown role '" + roleName + "': it is neither a built-in (" + BUILT_INS.keySet()
              + ") nor defined in roles/" + roleName + ".json. Check the 'roles' array in the identity "
              + "file, or add roles/" + roleName + ".json.");
    }

    /** Hot reload: evict a role's cached scopes when its {@code roles/<name>.json} changes (M4 plumbing). */
    void onConfigChange(@Observes ConfigurationChangedEvent event) {
        Path path = event.path();
        if (path.getNameCount() < 1 || !"roles".equals(path.getName(0).toString())) {
            return;
        }
        String fileName = path.getFileName().toString();
        if (!fileName.endsWith(".json")) {
            return; // ignore stray entries — only role files map to a role name
        }
        cache.remove(fileName.substring(0, fileName.lastIndexOf('.')));
    }
}
