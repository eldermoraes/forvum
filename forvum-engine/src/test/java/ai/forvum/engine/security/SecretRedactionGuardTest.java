package ai.forvum.engine.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.id.AgentId;
import ai.forvum.core.security.FilteringOutcome;
import ai.forvum.sdk.HookLayer;
import ai.forvum.sdk.OutputContext;

import org.junit.jupiter.api.Test;

import java.util.UUID;

/**
 * The bundled default guard maps {@link SecretRedactor} onto a {@link FilteringOutcome}: a secret →
 * {@code Redacted}, clean text → {@code Allowed}, and the opt-out toggle → {@code Allowed} unconditionally.
 */
class SecretRedactionGuardTest {

    private static final OutputContext CTX =
        new OutputContext(HookLayer.PRE_CHANNEL_EMIT, new AgentId("main"), UUID.randomUUID());

    @Test
    void redactsAMatchedSecretWhenEnabled() {
        SecretRedactionGuard guard = new SecretRedactionGuard();
        guard.enabled = true;
        FilteringOutcome o = guard.filter(CTX, "key sk-ABCdef123456ghi789 end");
        FilteringOutcome.Redacted r = assertInstanceOf(FilteringOutcome.Redacted.class, o);
        assertTrue(r.content().contains("sk-***"));
        assertEquals(1, r.redactions());
    }

    @Test
    void allowsCleanTextWhenEnabled() {
        SecretRedactionGuard guard = new SecretRedactionGuard();
        guard.enabled = true;
        FilteringOutcome o = guard.filter(CTX, "just a normal answer about the API");
        assertEquals("just a normal answer about the API",
            assertInstanceOf(FilteringOutcome.Allowed.class, o).content());
    }

    @Test
    void allowsUnconditionallyWhenDisabled() {
        SecretRedactionGuard guard = new SecretRedactionGuard();
        guard.enabled = false;
        FilteringOutcome o = guard.filter(CTX, "leak sk-ABCdef123456ghi789");
        FilteringOutcome.Allowed a = assertInstanceOf(FilteringOutcome.Allowed.class, o);
        assertEquals("leak sk-ABCdef123456ghi789", a.content(), "opt-out leaves the text untouched");
    }
}
