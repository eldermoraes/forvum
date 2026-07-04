package ai.forvum.engine.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.MemoryHit;
import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.MemoryQuery;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.security.OutputFilteredException;
import ai.forvum.engine.security.OutputGuardChain;
import ai.forvum.sdk.OutputContext;

import jakarta.enterprise.inject.Vetoed;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Pure unit tests (no Quarkus boot) for {@link EngineMemoryAccess}'s two defensive edge paths, driven by
 * stub collaborators the production guards never reach in v0.1:
 * <ul>
 *   <li><b>save</b> reports NOT-stored ({@code false}) when the DR-6a pre-memory-write filter Blocks the
 *       value — the {@code SecretRedactionGuard} only ever Redacts, so a Blocked outcome is stubbed.</li>
 *   <li><b>recall</b> degrades to an empty list when the local provider throws — the #175 degrade-safely
 *       posture, so a memory-store failure never aborts the turn.</li>
 * </ul>
 * Collaborators not on the exercised path stay null on purpose, so reaching them would NPE the test rather
 * than let it pass — which also red-checks each short-circuit.
 */
class EngineMemoryAccessUnitTest {

    @Test
    void saveReturnsFalseAndPersistsNothingWhenTheFilterBlocksTheValue() {
        EngineMemoryAccess access = new EngineMemoryAccess();
        access.outputGuards = new BlockingChain();

        boolean stored = ScopedValue.where(CurrentAgent.CURRENT_AGENT, new AgentId("blk-agent"))
                .call(() -> access.save("key", "a blocked value"));

        assertFalse(stored, "a value the pre-memory-write filter Blocks must be reported as not stored");
    }

    @Test
    void recallDegradesToEmptyWhenTheLocalProviderThrows() {
        EngineMemoryAccess access = new EngineMemoryAccess();
        access.localMemory = new ThrowingLocalProvider();

        List<MemoryHit> hits = ScopedValue.where(CurrentAgent.CURRENT_AGENT, new AgentId("deg-agent"))
                .call(() -> access.recall("anything"));

        assertTrue(hits.isEmpty(), "a provider failure degrades to no hits, never a turn-aborting throw");
    }

    /** Always Blocks — {@code @Vetoed} so it is not discovered as a second {@link OutputGuardChain} bean. */
    @Vetoed
    static final class BlockingChain extends OutputGuardChain {
        @Override
        public String enforce(OutputContext ctx, String candidate) {
            throw new OutputFilteredException("test: blocked", ctx.turnId());
        }
    }

    /** Always fails retrieval — {@code @Vetoed} so it is not a second {@link LocalMemoryProvider} bean. */
    @Vetoed
    static final class ThrowingLocalProvider extends LocalMemoryProvider {
        @Override
        public List<MemoryHit> retrieve(MemoryQuery query, MemoryPolicy policy) {
            throw new RuntimeException("test: memory provider unavailable");
        }
    }
}
