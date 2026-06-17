#!/usr/bin/env bash
# STUB ffmpeg for the voice-channel tests: NO real transcoding.
# It honors the same argv the production pipeline builds —
#   ffmpeg -y -i <in> -ar 16000 -ac 1 -c:a pcm_s16le <out.wav>
# — and just writes a placeholder byte to the output path (the whisper stub never decodes it), proving
# the transcode subprocess seam. The last positional argument is the output path.
set -euo pipefail

out=""
prev=""
for arg in "$@"; do
  prev="$arg"
done
out="$prev"

if [[ -z "$out" ]]; then
  echo "ffmpeg-stub: no output path" >&2
  exit 2
fi
printf 'WAV' > "$out"
