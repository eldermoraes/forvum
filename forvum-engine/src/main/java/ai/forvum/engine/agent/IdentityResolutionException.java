package ai.forvum.engine.agent;

/**
 * Thrown by {@link IdentityResolver#resolveEffective} when an agent's declared {@code identityId} fallback
 * names no configured {@code identities/<id>.json} (#168). The turn fails CLOSED: the engine surfaces it
 * as a terminal {@code identity_unresolved} error rather than silently degrade an unresolved user to the
 * permissive anonymous default, so a flow can never escalate past the configured fallback by being
 * unresolved. Engine-local + unchecked, mirroring the {@code OutputFilteredException} /
 * {@code BudgetExhaustedException} terminal-short-circuit pattern; the SDK/core stay exception-free.
 */
public class IdentityResolutionException extends RuntimeException {

    public IdentityResolutionException(String message) {
        super(message);
    }
}
