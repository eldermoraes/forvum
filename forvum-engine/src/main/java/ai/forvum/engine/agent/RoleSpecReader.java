package ai.forvum.engine.agent;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.RoleSpec;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Binds a raw {@code roles/<name>.json} (delivered by the M4-style {@code RoleReader}) to a typed core
 * {@link RoleSpec} (P2-11, mirrors {@code CronSpecReader}/{@code AgentSpecReader}). The required
 * {@code scopes} array lists {@link PermissionScope} names; an unknown scope fails via
 * {@link PermissionScope#fromName} so a hand-edited file surfaces a contextual error. An empty array is
 * a valid role that grants nothing.
 */
public final class RoleSpecReader {

    public RoleSpec parse(String name, JsonNode spec) {
        JsonNode scopesNode = spec.get("scopes");
        if (scopesNode == null || !scopesNode.isArray()) {
            throw new IllegalStateException(
                    "Role '" + name + "' is missing the required 'scopes' array. "
                  + "Check roles/" + name + ".json.");
        }
        Set<PermissionScope> scopes = new LinkedHashSet<>();
        for (JsonNode scope : scopesNode) {
            scopes.add(PermissionScope.fromName(scope.asText()));
        }
        return new RoleSpec(name, scopes);
    }
}
