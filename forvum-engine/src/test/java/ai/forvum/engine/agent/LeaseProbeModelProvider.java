package ai.forvum.engine.agent;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A test {@link ai.forvum.sdk.ModelProvider} (extension id {@code leaseprobe}) that records, at chat time,
 * whether the per-turn agent-spec lease ({@link AgentRegistry#CURRENT_AGENT_SPEC}) was bound and which
 * generation it carried — proving that a turn entry ({@code TurnService.dispatch}) binds the lease around
 * the whole turn (#178). It can also block a turn at model time via the latch pair so a test can mutate
 * config while the turn is in flight.
 */
@ApplicationScoped
public class LeaseProbeModelProvider extends AbstractModelProvider {

    static volatile boolean leaseBoundDuringTurn;
    static volatile long leasedGeneration = -1L;

    /** When both are set, the first turn to reach the model counts down {@code reachedModel} then awaits {@code releaseModel}. */
    static volatile CountDownLatch reachedModel;
    static volatile CountDownLatch releaseModel;

    static void resetProbe() {
        leaseBoundDuringTurn = false;
        leasedGeneration = -1L;
        reachedModel = null;
        releaseModel = null;
    }

    @Override
    public String extensionId() {
        return "leaseprobe";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                leaseBoundDuringTurn = AgentRegistry.CURRENT_AGENT_SPEC.isBound();
                if (leaseBoundDuringTurn) {
                    leasedGeneration = AgentRegistry.CURRENT_AGENT_SPEC.get().generation();
                }
                CountDownLatch reached = reachedModel;
                CountDownLatch release = releaseModel;
                if (reached != null && release != null) {
                    reachedModel = null; // block only the first turn that reaches the model
                    reached.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
            }
        };
    }
}
