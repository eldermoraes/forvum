#!/usr/bin/env bash
# STUB piper for the voice-channel tests: NO real voice model, no audio synthesis.
# It honors the same argv the production pipeline builds —
#   piper -m <voice.onnx> -f <out.wav>
# — reads the reply text from stdin, and writes a 44-byte RIFF/WAVE header (a minimal valid empty WAV)
# to the -f output path so a test can assert "a non-empty WAV landed in the outbox". The reply text is
# NOT embedded (the channel never logs/leaks it); the header is enough to prove the TTS subprocess seam.
#
# A test points piperBin at this script (chmod +x). The real piper round-trip is a @Tag("live") test,
# never gating the native compile.
set -euo pipefail

out=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -f) out="$2"; shift 2 ;;
    *) shift ;;
  esac
done

# Drain stdin so the writer side never blocks on a full pipe.
cat > /dev/null || true

if [[ -z "$out" ]]; then
  echo "piper-stub: no -f output path given" >&2
  exit 2
fi

# Minimal 44-byte WAV header (RIFF .... WAVE fmt  .... data ....), zero audio data.
printf 'RIFF' > "$out"
printf '\x24\x00\x00\x00' >> "$out"   # ChunkSize = 36
printf 'WAVE' >> "$out"
printf 'fmt ' >> "$out"
printf '\x10\x00\x00\x00' >> "$out"   # Subchunk1Size = 16
printf '\x01\x00' >> "$out"           # PCM
printf '\x01\x00' >> "$out"           # mono
printf '\x80\x3e\x00\x00' >> "$out"   # 16000 Hz
printf '\x00\x7d\x00\x00' >> "$out"   # byte rate
printf '\x02\x00' >> "$out"           # block align
printf '\x10\x00' >> "$out"           # 16 bits
printf 'data' >> "$out"
printf '\x00\x00\x00\x00' >> "$out"   # data size = 0
