package ai.forvum.engine.graph;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The LangGraph4j state for one supervisor turn (ULTRAPLAN section 5.5). It is a {@code class} extending
 * {@link AgentState}, NOT a record (reconciliation R1 — LangGraph4j requires a map-backed state with a
 * {@code Map<String,Object>} constructor + a {@link #SCHEMA} of channels).
 *
 * <p><strong>Reconciliation R6 (serialization):</strong> LangGraph4j serializes the state via
 * {@code ObjectOutputStream} on every step even with no checkpointer, and langchain4j message types are
 * NOT {@code Serializable}. So this state holds <em>only control signals</em> ({@code String}s and a
 * {@code List<String>} of worker digests — all serialization- and native-safe). The actual conversation
 * ({@code ChatMessage}s) lives in a mutable turn-scoped holder captured by the per-turn-compiled node
 * lambdas, never in the state. The graph is the control-flow skeleton; data flows through the holder.
 *
 * <p>Channels: {@link #WORKER_DIGESTS} accumulates (appender); {@link #ROUTE}, {@link #NEXT}, and
 * {@link #FINAL} overwrite (the default for keys absent from the SCHEMA).
 */
public final class GraphState extends AgentState {

    /** The route classification for this turn (a {@code RouteDecision} name). */
    public static final String ROUTE = "route";
    /** The conditional-edge signal a node emits to pick the next node ({@code tools}/{@code done}/...). */
    public static final String NEXT = "next";
    /** The final assistant text once the turn is done. */
    public static final String FINAL = "final";
    /** Compressed digests merged back from spawned workers (Isolate boundary), each a {@code String}. */
    public static final String WORKER_DIGESTS = "workerDigests";

    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            WORKER_DIGESTS, Channels.appender(ArrayList::new));

    public GraphState(Map<String, Object> data) {
        super(data);
    }

    /** The route decision for this turn, if {@code route} has run. */
    public Optional<String> route() {
        return this.value(ROUTE);
    }

    /** The next-node signal the last node emitted, if any. */
    public Optional<String> next() {
        return this.value(NEXT);
    }

    /** The final assistant text, if the turn has reached a terminal node. */
    public Optional<String> finalText() {
        return this.value(FINAL);
    }

    /** The worker digests merged so far. */
    public List<String> workerDigests() {
        return this.<List<String>>value(WORKER_DIGESTS).orElseGet(List::of);
    }
}
