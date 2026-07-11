package ai.forvum.tools.tts;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;

/**
 * The {@code tts.speak} tool spec (#186): synthesize speech from {@code text} to a WAV written under the
 * workspace, driving the operator-installed piper binary configured in {@code tools/tts.json}. It is
 * fs-write-class, so it declares {@link ToolSpec#userConfirmRequired()} {@code = false} (the 4-argument
 * constructor): the model controls only the DATA (the {@code text}, fed on stdin) and a voice NAME
 * resolved strictly against operator config; the PROGRAM ({@code piperBin}), the voice file, the argv
 * shape, and the output location are all operator/tool-fixed. {@code fs.write} (workspace-confined write)
 * likewise carries no confirm; {@code shell.exec} confirms only because the model chooses the program.
 *
 * <p>The parameters schema declares a required {@code text} string and an optional {@code voice} string
 * (a NAME resolved against the {@code voices} map in {@code tools/tts.json}) — both scalars, so the
 * engine's {@code ToolCallBridge} needs no new schema shape (unlike shell's {@code argv} array).
 */
public final class TtsSpeakTool {

    /** The tool this module contributes; executed by {@code TtsToolProvider}. */
    public static final ToolSpec SPEC = new ToolSpec(
            "tts.speak",
            "Synthesize speech from text to a WAV audio file written under the workspace, using the "
          + "operator-configured piper voice. Returns the workspace-relative path to the generated WAV. "
          + "Optionally select a configured voice by name.",
            PermissionScope.MEDIA_SYNTHESIZE,
            "{\"type\":\"object\",\"properties\":{"
          + "\"text\":{\"type\":\"string\","
          + "\"description\":\"the text to synthesize into speech\"},"
          + "\"voice\":{\"type\":\"string\","
          + "\"description\":\"optional name of a configured voice (from tools/tts.json 'voices'); "
          + "the default voice is used when omitted\"}},"
          + "\"required\":[\"text\"]}");

    private TtsSpeakTool() {
    }
}
