package ai.forvum.engine.agent;

import ai.forvum.engine.config.IdentityReader;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves a channel's {@code (channelId, nativeUserId)} into the configured {@code Identity} id
 * (ULTRAPLAN section 5.3). {@code Identity} maps {@code channelId -> nativeUserId} (forward); this builds
 * the inverse from {@code identities/<id>.json}. An unconfigured native user is unresolved (the channel
 * then treats the session as anonymous).
 */
@ApplicationScoped
public class IdentityResolver {

    /** The deliberately restricted identity bound for an unresolved user with no agent fallback (#168). */
    public static final String ANONYMOUS_IDENTITY = "anonymous";

    @Inject
    IdentityReader identities;

    /** The configured Identity id whose {@code channelAccounts[channelId] == nativeUserId}, if any. */
    public Optional<String> resolveIdentityId(String channelId, String nativeUserId) {
        for (String id : identities.ids()) {
            JsonNode spec = identities.read(id).orElse(null);
            if (spec == null) {
                continue;
            }
            JsonNode accounts = spec.get("channelAccounts");
            JsonNode account = accounts == null ? null : accounts.get(channelId);
            if (account != null && nativeUserId.equals(account.asText())) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }

    /**
     * The authorization roles {@code identityId} declares in {@code identities/<id>.json} (P2-11). Empty
     * when the file is absent (e.g. the anonymous session), or it declares no {@code roles} array — the
     * engine's {@code RoleRegistry} then applies the permissive default role. A single raw read by id (no
     * scan), consistent with this resolver's raw-{@link JsonNode} style.
     */
    public List<String> rolesFor(String identityId) {
        JsonNode spec = identities.read(identityId).orElse(null);
        if (spec == null) {
            return List.of();
        }
        JsonNode rolesNode = spec.get("roles");
        if (rolesNode == null || !rolesNode.isArray()) {
            return List.of();
        }
        List<String> roles = new ArrayList<>();
        rolesNode.forEach(role -> roles.add(role.asText()));
        return roles;
    }

    /**
     * The identity a turn effectively runs as (#168 precedence), with the role names that cap its scopes:
     *
     * <ol>
     *   <li>a RESOLVED channel identity ({@code channelAccounts[channelId] == nativeUserId}) — it always
     *       wins, carrying its own declared roles;</li>
     *   <li>else the agent's declared {@code fallbackIdentityId} (the {@code identityId} from the
     *       persona), carrying THAT identity's roles — so an unmapped user runs as the operator's
     *       configured fallback, not a free-for-all;</li>
     *   <li>else the deliberately restricted {@link #ANONYMOUS_IDENTITY}, capped by the
     *       {@link RoleRegistry#ANONYMOUS} role (no privileged scopes).</li>
     * </ol>
     *
     * <p>A non-null {@code fallbackIdentityId} that names no configured {@code identities/<id>.json} FAILS
     * CLOSED with an {@link IdentityResolutionException}: the caller surfaces an actionable error rather
     * than degrade to the permissive anonymous default. This guarantees no flow gains scopes by becoming
     * unresolved — the unresolved tail is the most restricted branch, never the most permissive.
     *
     * @param fallbackIdentityId the persona's {@code identityId} ({@code null} = no agent fallback)
     */
    public EffectiveIdentity resolveEffective(String channelId, String nativeUserId,
            String fallbackIdentityId) {
        Optional<String> resolved = resolveIdentityId(channelId, nativeUserId);
        if (resolved.isPresent()) {
            String id = resolved.get();
            return new EffectiveIdentity(id, rolesFor(id));
        }
        if (fallbackIdentityId != null) {
            if (identities.read(fallbackIdentityId).isEmpty()) {
                throw new IdentityResolutionException(
                    "Agent fallback identity '" + fallbackIdentityId + "' is not defined: no identities/"
                  + fallbackIdentityId + ".json. Define it or remove 'identityId' from the agent spec. "
                  + "Failing closed — an unresolved user is not granted the anonymous default.");
            }
            return new EffectiveIdentity(fallbackIdentityId, rolesFor(fallbackIdentityId));
        }
        return new EffectiveIdentity(ANONYMOUS_IDENTITY, List.of(RoleRegistry.ANONYMOUS));
    }
}
