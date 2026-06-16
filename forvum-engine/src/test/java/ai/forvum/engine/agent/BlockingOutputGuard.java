package ai.forvum.engine.agent;

import ai.forvum.core.security.FilteringOutcome;
import ai.forvum.sdk.AbstractOutputGuard;
import ai.forvum.sdk.OutputContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

/**
 * A test-only {@code OutputGuard} that always {@code Blocks}, used to drive the engine's Blocked →
 * terminal {@code output_filtered} path. It is an {@code @Alternative} with NO {@code @Priority} — so it
 * is INERT unless a {@code QuarkusTestProfile.getEnabledAlternatives()} enables it (a global
 * {@code @Priority} would enable it app-wide and block every other test's egress). Only
 * {@link OutputGuardBlockIT} enables it; every other module test composes just the real
 * {@code SecretRedactionGuard}.
 */
@Alternative
@ApplicationScoped
public class BlockingOutputGuard extends AbstractOutputGuard {

    static final String REASON = "test policy block";

    @Override
    public FilteringOutcome filter(OutputContext ctx, String candidate) {
        return new FilteringOutcome.Blocked(REASON);
    }
}
