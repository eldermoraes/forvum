package ai.forvum.engine.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;

import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.security.OutputFilteredException;
import ai.forvum.engine.security.OutputGuardChain;
import ai.forvum.sdk.OutputContext;

import jakarta.enterprise.inject.Vetoed;

import org.junit.jupiter.api.Test;

/**
 * Unit-level proof that {@link EngineMemoryAccess#save} reports NOT-stored ({@code false}) when the DR-6a
 * pre-memory-write filter Blocks the value — the suppress path. A pure unit test (no Quarkus boot): the
 * production {@code SecretRedactionGuard} only ever Redacts, so a Blocked outcome is driven here with a stub
 * chain that throws {@link OutputFilteredException}. The store/embedding collaborators stay null on purpose —
 * a Blocked value must short-circuit before either is touched, so reaching them would NPE the test rather
 * than let it pass, which also red-checks the short-circuit.
 */
class EngineMemoryAccessBlockedTest {

    @Test
    void saveReturnsFalseAndPersistsNothingWhenTheFilterBlocksTheValue() {
        EngineMemoryAccess access = new EngineMemoryAccess();
        access.outputGuards = new BlockingChain();

        boolean stored = ScopedValue.where(CurrentAgent.CURRENT_AGENT, new AgentId("blk-agent"))
                .call(() -> access.save("key", "a blocked value"));

        assertFalse(stored, "a value the pre-memory-write filter Blocks must be reported as not stored");
    }

    /** Always Blocks — {@code @Vetoed} so it is not discovered as a second {@link OutputGuardChain} bean. */
    @Vetoed
    static final class BlockingChain extends OutputGuardChain {
        @Override
        public String enforce(OutputContext ctx, String candidate) {
            throw new OutputFilteredException("test: blocked", ctx.turnId());
        }
    }
}
