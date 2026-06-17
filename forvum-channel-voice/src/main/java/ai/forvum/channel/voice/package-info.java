/**
 * The Voice channel (P2-3 / #28): Forvum's spoken-input/spoken-reply bridge through
 * <strong>operator-installed</strong> whisper.cpp (speech-to-text) and piper (text-to-speech) binaries,
 * both driven as external subprocesses via {@link java.lang.ProcessBuilder}.
 *
 * <p><strong>File-drop transport (maintainer-ratified for v0.1).</strong> v0.1 voice is NOT a live
 * microphone: an operator drops a recorded audio clip in an inbox and a synthesized reply WAV appears in
 * an outbox. A background virtual-thread worker polls the inbox (macOS {@code WatchService} latency,
 * Risk #7, makes a poll loop the deterministic choice). Live capture/playback and barge-in are a
 * documented follow-up — they would pull {@code javax.sound.sampled} or a live-capture binary, a much
 * larger native + cross-platform surface this design deliberately avoids.
 *
 * <p><strong>Native-clean by construction.</strong> The JVM links NO audio codec and touches no
 * JNI/{@code javax.sound.sampled}: all audio decode/encode happens in the EXTERNAL whisper/piper/ffmpeg
 * processes, so the channel's native surface is just {@code ProcessBuilder} (a plain posix fork/exec,
 * fully supported by GraalVM native-image), {@code java.nio.file}, and a virtual-thread polling loop —
 * all already proven native in this repo (the Signal event loop + the filesystem tool).
 *
 * <p><strong>Configuration.</strong> The operator installs whisper.cpp and piper and enables the channel
 * in {@code $FORVUM_HOME/channels/voice.json}:
 *
 * <pre>{@code
 * {
 *   "whisperBin": "/opt/whisper.cpp/main",
 *   "whisperModel": "/opt/models/ggml-base.en.bin",
 *   "piperBin": "/opt/piper/piper",
 *   "piperVoice": "/opt/voices/en_US-amy-medium.onnx",
 *   "ffmpegPath": "/usr/bin/ffmpeg",
 *   "allowedUserIds": ["alice"]
 * }
 * }</pre>
 *
 * <p><strong>Pipeline (per inbound file).</strong> Ensure a 16 kHz mono 16-bit WAV (a {@code .wav} is
 * assumed conformant; any other extension requires an {@code ffmpegPath} transcode, else the file is
 * rejected) → STT via {@code [whisperBin, -m, model, -f, wav, -nt, -otxt, -of, <stem>]} → drive a turn
 * via the SDK {@code ChannelTurnDriver} → accumulate the reply via the byte-identical {@code render}
 * (streaming Option B: one {@code TokenDelta}, then the terminal {@code Done}; a batch TTS of the whole
 * reply, per-token spoken streaming is a later non-breaking upgrade) → TTS via
 * {@code echo TEXT | [piperBin, -m, voice, -f, out]} → atomic move into the outbox under the inbound stem.
 *
 * <p><strong>Authorization + privacy.</strong> An inbound file's native user id is its
 * {@code <id>__rest.wav} prefix, or the single-user default {@code voice-local}; {@code allowedUserIds}
 * (empty = any sender, single-user convenience) restricts who may drive a turn, and a refused file is
 * dropped with no turn and no reply. The transcript and the reply text are sensitive and are NEVER logged
 * (only file names and lengths) — the Signal logging-discipline lesson.
 *
 * <p><strong>Absent config → warn + no-op.</strong> With no {@code channels/voice.json}, the channel
 * disabled, or the whisper/piper binaries unconfigured, the poll worker is not started and the bean logs
 * + returns — never throwing, never blocking — so the CI native no-config boot stays graceful.
 *
 * <p><strong>Scope (v0.1).</strong> The real whisper/piper round-trip is a {@code @Tag("live")} test,
 * default-off, never gating the native compile (the Signal connect-only / Ollama native-turn precedent);
 * sandbox isolation of the subprocesses (#27) and the live whisper/piper integration (#28's live arm) are
 * documented follow-ups. Forvum does not install whisper/piper — the operator does.
 */
package ai.forvum.channel.voice;
