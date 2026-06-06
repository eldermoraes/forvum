package ai.forvum.engine.agent;

import ai.forvum.engine.config.IdentityReader;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
}
