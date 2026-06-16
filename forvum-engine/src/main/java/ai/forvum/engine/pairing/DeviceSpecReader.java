package ai.forvum.engine.pairing;

import ai.forvum.core.PermissionScope;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Binds a raw {@code devices/<id>.json} (delivered by the M4 {@code DeviceReader}) to a typed
 * {@link Device} (P2-4, mirrors {@code RoleSpecReader}). The required {@code identityId} names the
 * {@code Identity} the device reuses; the optional {@code token} carries a pairing shared-secret
 * (absent ⇒ empty); {@code revoked} defaults to false. {@code requestedScopes}/{@code approvedScopes}
 * (P2-PAIR-SCOPE #44) are optional string arrays of {@link PermissionScope} names (absent ⇒ empty);
 * {@code decisionReason} is the optional reason code from the last approve/reject. A file missing
 * {@code identityId}, or naming an unknown scope, surfaces a contextual {@link IllegalStateException}
 * naming the suspect {@code devices/<id>.json}.
 */
public final class DeviceSpecReader {

    public Device parse(String id, JsonNode spec) {
        JsonNode identityNode = spec.get("identityId");
        if (identityNode == null || identityNode.asText().isBlank()) {
            throw new IllegalStateException(
                    "Device '" + id + "' is missing the required 'identityId'. A paired device must name "
                  + "the Identity it reuses. Check devices/" + id + ".json.");
        }
        JsonNode tokenNode = spec.get("token");
        String token = tokenNode == null ? "" : tokenNode.asText();
        JsonNode revokedNode = spec.get("revoked");
        boolean revoked = revokedNode != null && revokedNode.asBoolean();
        Set<PermissionScope> requested = parseScopes(id, spec.get("requestedScopes"), "requestedScopes");
        Set<PermissionScope> approved = parseScopes(id, spec.get("approvedScopes"), "approvedScopes");
        JsonNode reasonNode = spec.get("decisionReason");
        String decisionReason = reasonNode == null || reasonNode.isNull() ? null : reasonNode.asText();
        return new Device(id, token, identityNode.asText(), revoked, requested, approved, decisionReason);
    }

    /** Parse an optional JSON string-array of scope names; an absent field is an empty set. */
    private static Set<PermissionScope> parseScopes(String id, JsonNode arrayNode, String field) {
        if (arrayNode == null || arrayNode.isNull()) {
            return Set.of();
        }
        if (!arrayNode.isArray()) {
            throw new IllegalStateException(
                    "Device '" + id + "' field '" + field + "' must be a JSON array of scope names. "
                  + "Check devices/" + id + ".json.");
        }
        Set<PermissionScope> scopes = new LinkedHashSet<>();
        for (JsonNode element : arrayNode) {
            try {
                scopes.add(PermissionScope.fromName(element.asText()));
            } catch (IllegalStateException e) {
                throw new IllegalStateException(
                        "Device '" + id + "' field '" + field + "' names an unknown scope '"
                      + element.asText() + "'. Check devices/" + id + ".json. " + e.getMessage());
            }
        }
        return scopes;
    }
}
