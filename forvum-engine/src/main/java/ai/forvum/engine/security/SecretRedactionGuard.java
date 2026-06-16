package ai.forvum.engine.security;

import ai.forvum.core.security.FilteringOutcome;
import ai.forvum.sdk.AbstractOutputGuard;
import ai.forvum.sdk.OutputContext;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The bundled default {@code OutputGuard} (P2-OUTPUTGUARD, DR-6a §9.2): a secrets-only redactor that is
 * <strong>on by default</strong> (opt-out via {@code forvum.output-guard.secret-redaction.enabled=false}),
 * so a fresh install never leaks a model-echoed API key/token without any configuration. It only ever
 * {@code Redacts} (or {@code Allows}) — full {@code Block} is reserved for policy-configured hard
 * categories, which v0.1 does not ship; a plugin guard supplies {@code Blocked} when needed and the engine
 * chain enforces it.
 *
 * <p>The redaction logic lives in the pure {@link SecretRedactor}; this bean only wires the toggle and maps
 * its {@link SecretRedactor.Result} onto a {@link FilteringOutcome}.
 */
@ApplicationScoped
public class SecretRedactionGuard extends AbstractOutputGuard {

    @ConfigProperty(name = "forvum.output-guard.secret-redaction.enabled", defaultValue = "true")
    boolean enabled;

    @Override
    public FilteringOutcome filter(OutputContext ctx, String candidate) {
        if (!enabled || candidate == null || candidate.isEmpty()) {
            return new FilteringOutcome.Allowed(candidate);
        }
        SecretRedactor.Result result = SecretRedactor.redact(candidate);
        if (result.redactions() == 0) {
            return new FilteringOutcome.Allowed(candidate);
        }
        return new FilteringOutcome.Redacted(result.content(), result.redactions());
    }
}
