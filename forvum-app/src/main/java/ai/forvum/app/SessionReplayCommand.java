package ai.forvum.app;

import ai.forvum.engine.replay.MessageSegment;
import ai.forvum.engine.replay.ReplaySegment;
import ai.forvum.engine.replay.ReplaySession;
import ai.forvum.engine.replay.SessionReplayer;
import ai.forvum.engine.replay.ToolSegment;

import jakarta.inject.Inject;

import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * {@code forvum replay <sessionId>} (P2-8, ULTRAPLAN section 7.2 item 8): reproduce a stored session's
 * recorded message and tool sequence to stdout, for debugging and regression inspection. It reads the
 * {@code messages} and {@code tool_invocations} a prior turn wrote (via the engine {@link SessionReplayer})
 * and prints them in turn order — it does NOT re-invoke the model (re-execution-with-substitution is the
 * Phase-3 extension P3-9). Exits 0 for an existing session, 1 when the id matches no stored session.
 *
 * <p>Session ids are {@code channelId:nativeUserId} (e.g. {@code cli:alice}, {@code telegram:12345}): a
 * {@code forvum ask} turn writes {@code cli:<os-user>}. Unlike {@code --help}/{@code --version}/{@code init}/
 * {@code doctor}, {@code replay} is deliberately NOT a {@code CommandMode} one-shot — it reads the SQLite DB,
 * so it boots the full Flyway/Panache path (like {@code ask}). picocli routes {@code replay} here (not to
 * {@link RootCommand#call()}), so no channel/server dispatch runs.
 */
@CommandLine.Command(
        name = "replay",
        description = "Replay a stored session's recorded message and tool sequence.")
public class SessionReplayCommand implements Callable<Integer> {

    @Inject
    SessionReplayer replayer;

    @CommandLine.Parameters(
            arity = "1",
            paramLabel = "<sessionId>",
            description = "Session id, formatted channelId:nativeUserId (e.g. cli:alice). "
                    + "A 'forvum ask' turn records the session cli:<your-os-user>.")
    String sessionId;

    @Override
    public Integer call() {
        ReplaySession session = replayer.replay(sessionId);
        if (!session.found()) {
            System.err.println("No session '" + sessionId + "' found. "
                    + "Session ids are channelId:nativeUserId (e.g. cli:alice); run a turn first.");
            return 1;
        }

        System.out.println("=== Session " + session.sessionId() + " ===");
        System.out.println("agent=" + session.agentId()
                + "  channel=" + session.channelId()
                + "  identity=" + session.identityId()
                + "  started=" + session.startedAt());
        System.out.println();

        for (ReplaySegment segment : session.segments()) {
            switch (segment) {
                case MessageSegment message -> System.out.println("[" + message.role() + "] " + message.content());
                case ToolSegment tool -> printTool(tool);
            }
        }

        System.out.println();
        System.out.println("Replayed " + session.messageCount() + " message(s) and "
                + session.toolCount() + " tool invocation(s).");
        return 0;
    }

    private static void printTool(ToolSegment tool) {
        String latency = tool.latencyMs() == null ? "n/a" : tool.latencyMs() + "ms";
        System.out.println("[tool] " + tool.toolName() + "  status=" + tool.status() + "  latency=" + latency);
        System.out.println("       args: " + tool.arguments());
        if (tool.result() != null) {
            System.out.println("       result: " + tool.result());
        }
    }
}
