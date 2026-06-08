package ai.forvum.core.id;

import java.util.List;
import java.util.Map;

/**
 * A cross-channel user identity, materialized from {@code identities/<id>.json} (ULTRAPLAN section 4.1).
 * {@code channelAccounts} maps a channel id (e.g. {@code "telegram"}, {@code "tui"}) to that channel's
 * native user id; each channel's inbound handler resolves a native id into one {@code Identity}
 * (section 5.3).
 *
 * <p>{@code roles} are the authorization roles the identity declares (P2-11, ULTRAPLAN section 4.3.4):
 * each role name resolves — via the engine's role registry — to a set of {@code PermissionScope}s, and
 * the tool executor denies a tool whose required scope is outside the union of the identity's roles'
 * scopes. The field is additive: an identity file predating RBAC declares no {@code roles} and is
 * treated as unrestricted (the engine maps an empty role list to the permissive built-in default role).
 *
 * <p>{@code channelAccounts} and {@code roles} are defensively copied to immutable collections by the
 * canonical constructor.
 */
public record Identity(String id, String displayName, Map<String, String> channelAccounts,
                       List<String> roles) {
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
        if (roles == null) {
            roles = List.of(); // an identity file with no 'roles' key is unrestricted (backward compatible)
        } else {
            for (String role : roles) {
                if (role == null || role.isBlank()) {
                    throw new IllegalStateException(
                        "Identity '" + id + "' roles must not contain null or blank entries. Check the "
                      + "'roles' array in identities/" + id + ".json.");
                }
            }
            roles = List.copyOf(roles);
        }
        channelAccounts = Map.copyOf(channelAccounts);
    }

    /**
     * Backward-compatible constructor for an identity with no declared roles (an empty role list — the
     * engine treats it as the permissive default). Mirrors an {@code identities/<id>.json} that predates
     * the P2-11 {@code roles} field.
     */
    public Identity(String id, String displayName, Map<String, String> channelAccounts) {
        this(id, displayName, channelAccounts, List.of());
    }
}
