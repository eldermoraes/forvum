package ai.forvum.tools.mediagen;

/**
 * A media-generation tool failure (#187): configuration gaps, argument errors, unsupported kinds, and
 * backend failures. The engine's ToolExecutor catches it generically, audits the invocation
 * {@code error}, and renders the message to the model — the turn completes (the {@code TtsException}
 * posture). Every message is actionable: it names the file/field to set or the capability gap.
 */
public class MediaGenException extends RuntimeException {

    public MediaGenException(String message) {
        super(message);
    }
}
