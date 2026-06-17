#!/usr/bin/env bash
# STUB whisper.cpp for the voice-channel tests: NO real model, no audio decoding.
# It honors the same argv the production pipeline builds —
#   whisper -m <model> -f <wav> -nt -otxt -of <stem>
# — and, when -otxt + -of are present, writes "<stem>.txt" with a canned transcript derived from the
# input WAV's file name (so a test can assert the transcript reached the turn). It also echoes the
# transcript to stdout (the production code falls back to stdout when no .txt was produced).
#
# A test points whisperBin at this script (chmod +x). The real whisper.cpp round-trip is a @Tag("live")
# test, never gating the native compile (the Signal connect-only / Ollama native-turn precedent).
set -euo pipefail

wav=""
stem=""
otxt=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    -f) wav="$2"; shift 2 ;;
    -of) stem="$2"; shift 2 ;;
    -otxt) otxt=1; shift ;;
    *) shift ;;
  esac
done

base="$(basename "${wav:-clip.wav}")"
transcript="transcript of ${base}"

if [[ "$otxt" -eq 1 && -n "$stem" ]]; then
  printf '%s\n' "$transcript" > "${stem}.txt"
fi
printf '%s\n' "$transcript"
