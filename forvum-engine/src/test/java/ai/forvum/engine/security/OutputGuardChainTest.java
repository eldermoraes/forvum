package ai.forvum.engine.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.id.AgentId;
import ai.forvum.core.security.FilteringOutcome;
import ai.forvum.sdk.AbstractOutputGuard;
import ai.forvum.sdk.HookLayer;
import ai.forvum.sdk.OutputContext;
import ai.forvum.sdk.OutputGuard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

/**
 * The engine composition fold (P2-OUTPUTGUARD, DR-6a §9.2.3): fail-closed and most-restrictive-wins —
 * {@code Blocked} dominates {@code Redacted} dominates {@code Allowed}, redactions chain + union, and a
 * throwing/null guard folds to {@code Blocked}. {@code enforce} maps {@code Blocked} to a thrown
 * {@link OutputFilteredException}.
 */
class OutputGuardChainTest {

    private static final OutputContext CTX =
        new OutputContext(HookLayer.PRE_CHANNEL_EMIT, new AgentId("main"), UUID.randomUUID());

    @Test
    void noGuardsPassesTheCandidateThroughUnchanged() {
        FilteringOutcome o = OutputGuardChain.compose(List.of(), CTX, "hello");
        assertEquals(new FilteringOutcome.Allowed("hello"), o);
    }

    @Test
    void allowOnlyChainStaysAllowed() {
        FilteringOutcome o = OutputGuardChain.compose(List.of(allow(), allow()), CTX, "hello");
        assertEquals("hello", assertInstanceOf(FilteringOutcome.Allowed.class, o).content());
    }

    @Test
    void redactionsChainAndCountsUnion() {
        FilteringOutcome o = OutputGuardChain.compose(
            List.of(replace("a", "X", 1), replace("b", "Y", 2)), CTX, "ab ab");
        FilteringOutcome.Redacted r = assertInstanceOf(FilteringOutcome.Redacted.class, o);
        assertEquals("XY XY", r.content(), "the second guard sees the first's output");
        assertEquals(3, r.redactions(), "counts union across guards");
    }

    @Test
    void blockedDominatesAndShortCircuits() {
        FilteringOutcome o = OutputGuardChain.compose(
            List.of(replace("a", "X", 1), block("policy"), throwing()), CTX, "ab");
        assertEquals("policy", assertInstanceOf(FilteringOutcome.Blocked.class, o).reason());
    }

    @Test
    void aThrowingGuardIsFailClosedToBlocked() {
        FilteringOutcome o = OutputGuardChain.compose(List.of(throwing()), CTX, "hello");
        assertTrue(assertInstanceOf(FilteringOutcome.Blocked.class, o).reason().contains("failed"));
    }

    @Test
    void aNullReturningGuardIsFailClosedToBlocked() {
        FilteringOutcome o = OutputGuardChain.compose(List.of(returningNull()), CTX, "hello");
        assertInstanceOf(FilteringOutcome.Blocked.class, o);
    }

    @Test
    void enforceReturnsTheRedactedTextOnRedacted() {
        String egress = OutputGuardChain.enforce(List.of(replace("a", "X", 1)), CTX, "aaa");
        assertEquals("XXX", egress);
    }

    @Test
    void enforceThrowsOutputFilteredExceptionOnBlocked() {
        OutputFilteredException ex = assertThrows(OutputFilteredException.class,
            () -> OutputGuardChain.enforce(List.of(block("leak")), CTX, "secret"));
        assertEquals(CTX.turnId(), ex.turnId());
        assertEquals("filtered", ex.reason());
    }

    // --- test guards (AbstractOutputGuard is an abstract class, so anonymous subclasses, not lambdas) ---

    private static OutputGuard allow() {
        return new AbstractOutputGuard() {
            @Override public FilteringOutcome filter(OutputContext ctx, String c) {
                return new FilteringOutcome.Allowed(c);
            }
        };
    }

    private static OutputGuard replace(String from, String to, int count) {
        return new AbstractOutputGuard() {
            @Override public FilteringOutcome filter(OutputContext ctx, String c) {
                return new FilteringOutcome.Redacted(c.replace(from, to), count);
            }
        };
    }

    private static OutputGuard block(String reason) {
        return new AbstractOutputGuard() {
            @Override public FilteringOutcome filter(OutputContext ctx, String c) {
                return new FilteringOutcome.Blocked(reason);
            }
        };
    }

    private static OutputGuard throwing() {
        return new AbstractOutputGuard() {
            @Override public FilteringOutcome filter(OutputContext ctx, String c) {
                throw new RuntimeException("boom");
            }
        };
    }

    private static OutputGuard returningNull() {
        return new AbstractOutputGuard() {
            @Override public FilteringOutcome filter(OutputContext ctx, String c) {
                return null;
            }
        };
    }
}
