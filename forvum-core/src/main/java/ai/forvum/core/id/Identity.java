package ai.forvum.core.id;

import java.util.Map;

/**
 * A cross-channel user identity, materialized from {@code identities/<id>.json} (ULTRAPLAN section 4.1).
 * {@code channelAccounts} maps a channel id (e.g. {@code "telegram"}, {@code "tui"}) to that channel's
 * native user id; each channel's inbound handler resolves a native id into one {@code Identity}
 * (section 5.3). Role-based authorization is an orthogonal Phase 2 layer and is not modeled here.
 *
 * <p>{@code channelAccounts} is defensively copied to an immutable map by the canonical constructor.
 */
public record Identity(String id, String displayName, Map<String, String> channelAccounts) {
    public Identity {
        if (id == null || id.isBlank() || !id.strip().equals(id)) {
            throw new IllegalStateException(
                "Identity id must be a non-blank token without leading/trailing "
              + "whitespace. Got: '" + id + "'. Check identities/<id>.json filename.");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalStateException(
                "Identity displayName must be non-null and non-blank. Got: '" + displayName
              + "'. Check the 'displayName' field in identities/<id>.json.");
        }
        if (channelAccounts == null) {
            throw new IllegalStateException(
                "Identity channelAccounts must be non-null (use an empty map for none). "
              + "A null indicates a config-parse wiring bug.");
        }
        for (var entry : channelAccounts.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalStateException(
                    "Identity channelAccounts must not contain null keys or values. Check the "
                  + "'channelAccounts' object in identities/<id>.json for a missing channel id or "
                  + "native user id.");
            }
        }
        channelAccounts = Map.copyOf(channelAccounts);
    }
}
