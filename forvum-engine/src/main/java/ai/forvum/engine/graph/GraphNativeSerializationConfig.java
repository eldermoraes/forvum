package ai.forvum.engine.graph;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;

/**
 * Native-image serialization registration for LangGraph4j's per-step state clone (reconciliation R6).
 *
 * <p>LangGraph4j clones the {@link GraphState} data map via {@code ObjectOutputStream} on <em>every</em>
 * node step (even with no checkpointer), and a GraalVM native image can only serialize concrete types that
 * were registered at build time. {@code GraphState} holds only {@code String}s (which {@code ObjectOutputStream}
 * special-cases, no registration needed) and a {@code List<String>} produced by a
 * {@code Channels.appender(ArrayList::new)} channel — so {@code java.util.ArrayList} is the single type the
 * native image must register for serialization. Without it the first graph step throws
 * {@code UnsupportedFeatureError: "SerializationConstructorAccessor class not found ... java.util.ArrayList"}
 * and the turn fails with "Supervisor graph failed" — the gap that Risk #5 (the deferred native real-provider
 * turn smoke) was meant to catch. This holder is build-time-scanned by Quarkus regardless of reachability.
 */
@RegisterForReflection(targets = { ArrayList.class }, serialization = true)
public final class GraphNativeSerializationConfig {

    private GraphNativeSerializationConfig() {
    }
}
