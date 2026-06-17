#!/usr/bin/env bash
# STUB piper that FAILS (nonzero exit) for the voice-channel TTS-failure test: it drains stdin and exits
# 1 without writing the output WAV, so the pipeline's "TTS failed => no outbox file, logged" branch runs.
set -euo pipefail
cat > /dev/null || true
echo "piper-fail-stub: synthesis failed" >&2
exit 1
