package ai.forvum.engine.agent;

import java.util.List;

/**
 * The identity a turn effectively runs as, resolved by {@link IdentityResolver#resolveEffective} via the
 * #168 precedence chain (resolved channel identity &rarr; the agent's declared {@code identityId} fallback
 * &rarr; the deliberately restricted anonymous identity), together with the role names whose union forms
 * the caller's effective scopes. The engine binds {@code identityId} on the session/ledger (accountability
 * and tenant isolation) and feeds {@code roleNames} to {@link RoleRegistry#effectiveScopes}
 * (authorization). {@code roleNames} is {@code [}{@link RoleRegistry#ANONYMOUS}{@code ]} for the anonymous
 * tail — never empty — so an unresolved session cannot collapse to the permissive empty-roles default.
 */
public record EffectiveIdentity(String identityId, List<String> roleNames) {

    public EffectiveIdentity {
        if (identityId == null || identityId.isBlank()) {
            throw new IllegalStateException(
                "EffectiveIdentity identityId must be non-blank — every turn runs as some identity.");
        }
        roleNames = roleNames == null ? List.of() : List.copyOf(roleNames);
    }
}
