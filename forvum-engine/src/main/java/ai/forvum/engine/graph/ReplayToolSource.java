package ai.forvum.engine.graph;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves recorded tool outputs during a session replay-with-substitution (#57): a FIFO queue of recorded
 * results per tool name. The Nth call to a tool during the rerun consumes the Nth recorded result for that
 * tool name (the order the original turn produced them). A miss — a tool called more often in the rerun
 * than in the recording, or a tool the recording never called — yields the synthetic marker
 * {@link #UNAVAILABLE} so the rerun stays well-formed and deterministic: a replay NEVER re-executes a real
 * tool, so a substituted-model rerun is compared against the SAME tool outputs the original turn saw.
 */
public final class ReplayToolSource {

    /** The synthetic result fed back when the recording has no (more) output for a tool. */
    public static final String UNAVAILABLE = "recorded output unavailable";

    private final Map<String, Deque<String>> recorded = new HashMap<>();

    /**
     * @param invocations the original session's tool invocations reduced to (name, result), ALREADY
     *                    ordered oldest-first (the order the original turn produced them)
     */
    public ReplayToolSource(List<RecordedTool> invocations) {
        for (RecordedTool tool : invocations) {
            recorded.computeIfAbsent(tool.toolName(), key -> new ArrayDeque<>())
                    .addLast(tool.result() == null ? "" : tool.result());
        }
    }

    /** The next recorded result for {@code toolName} (FIFO), or {@link #UNAVAILABLE} on a miss. */
    public String next(String toolName) {
        Deque<String> queue = recorded.get(toolName);
        if (queue == null || queue.isEmpty()) {
            return UNAVAILABLE;
        }
        return queue.pollFirst();
    }

    /** A recorded tool invocation reduced to the replay-relevant primitives. */
    public record RecordedTool(String toolName, String result) {
    }
}
