package ai.forvum.engine.pairing;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Binds a raw {@code devices/<id>.json} (delivered by the M4 {@code DeviceReader}) to a typed
 * {@link Device} (P2-4, mirrors {@code RoleSpecReader}). The required {@code identityId} names the
 * {@code Identity} the device reuses; the optional {@code token} carries a pairing shared-secret
 * (absent ⇒ empty); {@code revoked} defaults to false. A file missing {@code identityId} surfaces a
 * contextual {@link IllegalStateException} naming the suspect {@code devices/<id>.json}.
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
        return new Device(id, token, identityNode.asText(), revoked);
    }
}
