/**
 * The text-to-speech tool (#186): Forvum's model-callable {@code tts.speak} action, synthesizing speech
 * from text to a WAV written under the workspace by driving an <strong>operator-installed</strong> piper
 * binary as an external subprocess via {@link java.lang.ProcessBuilder}.
 *
 * <p>OpenClaw exposes TTS as a model-callable tool; Forvum has the piper synthesis machinery only inside
 * the voice <em>channel</em> ({@code forvum-channel-voice}, a file-drop pipeline). This module lifts that
 * capability into an ordinary turn on any channel: a Layer-3 tool the model invokes directly, gated by the
 * new {@link ai.forvum.core.PermissionScope#MEDIA_SYNTHESIZE} scope.
 *
 * <p><strong>Native-clean by construction.</strong> The JVM links NO audio codec: piper does the
 * synthesis in an EXTERNAL process, so the module's native surface is just {@code ProcessBuilder} (a plain
 * posix fork/exec, fully supported by GraalVM native-image) and {@code java.nio.file}. The config is a
 * hand-parsed {@code JsonNode} tree-walk into a plain record (no reflective binding), so nothing carries
 * {@code @RegisterForReflection}.
 *
 * <p><strong>Configuration.</strong> The operator installs piper and configures the tool in
 * {@code $FORVUM_HOME/tools/tts.json}:
 *
 * <pre>{@code
 * {
 *   "piperBin": "/opt/piper/piper",
 *   "piperVoice": "/opt/voices/en_US-amy-medium.onnx",
 *   "voices": {
 *     "amy": "/opt/voices/en_US-amy-medium.onnx",
 *     "ryan": "/opt/voices/en_US-ryan-high.onnx"
 *   },
 *   "timeoutSeconds": 120
 * }
 * }</pre>
 *
 * <p>{@code piperBin} + {@code piperVoice} (the default voice) are required; {@code voices} is an optional
 * NAME → ONNX-path map so the {@code tts.speak(text, [voice])} parameter selects a voice by name — the
 * model can never point piper at an arbitrary filesystem path.
 *
 * <p><strong>Behavior.</strong> {@code tts.speak(text, [voice])} resolves the voice, writes a temp WAV in
 * {@code <workspace>/tts/}, runs {@code [piperBin, -m, <voice>, -f, <temp>]} with the text on stdin, then
 * atomically moves the result to a generated collision-free name
 * ({@code speech-<yyyyMMdd-HHmmss>-<8 hex>.wav}) and returns its workspace-relative path plus byte size.
 *
 * <p><strong>Inert when unconfigured → actionable error.</strong> With no {@code tools/tts.json} the tool
 * is still visible (a constant SPEC, zero boot IO) but every call returns an actionable "not configured"
 * error to the model — never a crash, never a hang (timeout + kill-tree + a bounded post-settle drain).
 *
 * <p><strong>userConfirmRequired = false.</strong> {@code tts.speak} is fs-write-class (operator-fixed
 * program + argv + voice + output location; the model controls only the stdin text and a config-resolved
 * voice name), so it does not require user confirmation — unlike {@code shell.exec}, which confirms only
 * because the model chooses the program.
 *
 * <p><strong>Scope (v0.1).</strong> The real piper synthesis is a {@code @Tag("live")} test, default-off,
 * never gating the native compile (the shell/voice precedent); Forvum does not install piper — the
 * operator does. A voice-provider abstraction (piper is THE backend) is {@code GenerationProvider} (#187)
 * territory, not this module.
 */
package ai.forvum.tools.tts;
