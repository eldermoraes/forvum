package ai.forvum.tools.tts;

/**
 * Thrown when {@code tts.speak} cannot synthesize audio — the tool is unconfigured, the requested voice
 * is unknown, the {@code text} argument is missing/blank, or the piper process failed (could not start,
 * exited non-zero, timed out, or produced no audio) (#186). The engine's {@code ToolExecutor} catches it,
 * records the invocation {@code error}, and rethrows the message to the model; the turn completes.
 *
 * <p>{@code error} is the correct audit outcome for a tool-internal refusal — {@code denied} is the
 * engine's pre-action belt/RBAC verdict, which a Layer-3 tool cannot and must not emit (P2-2 DP-9).
 */
public final class TtsException extends RuntimeException {

    public TtsException(String message) {
        super(message);
    }
}
