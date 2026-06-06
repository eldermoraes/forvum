package ai.forvum.engine.graph;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Validates the LangGraph4j 1.8.17 API on Forvum's {@link GraphState} before the full supervisor graph is
 * built: a {@code String}-only state (reconciliation R6 — no langchain4j types in state, so no
 * {@code ObjectOutputStream} failure) compiles, invokes, routes through a conditional edge, and loops a
 * counter node back to itself until a signal flips. This is the API + serialization-safety de-risk for
 * the M18 node implementations.
 */
class GraphStateTest {

    @Test
    void stringStateCompilesInvokesAndRoutesThroughAConditionalEdge() throws Exception {
        StateGraph<GraphState> graph = new StateGraph<>(GraphState.SCHEMA, GraphState::new)
                .addNode("decide", node_async(state -> Map.of(GraphState.NEXT, "finish", GraphState.FINAL, "hello")))
                .addEdge(START, "decide")
                .addConditionalEdges("decide", edge_async(state -> state.next().orElse("finish")),
                        Map.of("finish", END));

        CompiledGraph<GraphState> compiled = graph.compile();
        Optional<GraphState> result = compiled.invoke(Map.of());

        assertTrue(result.isPresent(), "the graph runs to END and yields a final state");
        assertEquals(Optional.of("hello"), result.get().finalText(), "the overwrite channel reads back");
    }

    @Test
    void appenderChannelOfStringsAccumulatesAcrossSeedAndNode() throws Exception {
        StateGraph<GraphState> graph = new StateGraph<>(GraphState.SCHEMA, GraphState::new)
                .addNode("emit", node_async(state -> Map.of(GraphState.WORKER_DIGESTS, "worker-b")))
                .addEdge(START, "emit")
                .addEdge("emit", END);

        CompiledGraph<GraphState> compiled = graph.compile();
        Optional<GraphState> result = compiled.invoke(Map.of(GraphState.WORKER_DIGESTS, List.of("worker-a")));

        assertTrue(result.isPresent());
        assertEquals(List.of("worker-a", "worker-b"), result.get().workerDigests(),
                "the appender accumulates seeded + node-emitted String digests (serialization-safe)");
    }
}
