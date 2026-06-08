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
}
