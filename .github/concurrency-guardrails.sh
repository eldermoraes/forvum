#!/usr/bin/env bash
# CI concurrency guardrails (CLAUDE.md section 11, ULTRAPLAN section 3.8). The engine and every
# channel hot path are virtual-thread-first and lock-free. Static scan of forvum-engine +
# forvum-channel-* src/main for two violations, each gated by an allowlist:
#   1. `synchronized`            -> use ReentrantLock / java.util.concurrent (allowlist: pinning-allowlist.txt)
#   2. Mutiny / Reactor imports  -> reactive where a virtual thread suffices (allowlist: vt-allowlist.txt)
# The complementary runtime check (jdk.tracePinnedThreads "Thread pinned" scan) lives in ci.yml.
set -euo pipefail
cd "$(dirname "$0")/.."

# Expand the channel glob; drop it if no channel module exists yet.
roots=(forvum-engine/src/main)
for d in forvum-channel-*/src/main; do
    [ -d "$d" ] && roots+=("$d")
done

# Strip comments + blank lines from an allowlist, yielding fixed-string patterns (never empty: a
# lone sentinel keeps `grep -F` from treating an empty pattern set as "match everything").
patterns() {
    { grep -vE '^[[:space:]]*(#|$)' "$1" 2>/dev/null || true; printf '\0__never_matches__\n'; }
}

fail=0

# 1. synchronized ban — exclude comment lines (the only legitimate `synchronized` mentions are Javadoc).
sync_hits=$(grep -rnE '\bsynchronized\b' "${roots[@]}" --include='*.java' 2>/dev/null \
    | grep -vE ':[0-9]+:[[:space:]]*(\*|//|/\*)' \
    | grep -vFf <(patterns pinning-allowlist.txt) || true)
if [ -n "$sync_hits" ]; then
    echo "ERROR: 'synchronized' in a virtual-thread hot path (use ReentrantLock / java.util.concurrent):"
    echo "$sync_hits"
    fail=1
fi

# 2. Mutiny / Reactor import ban.
mutiny_hits=$(grep -rnE '^[[:space:]]*import[[:space:]]+(io\.smallrye\.mutiny|reactor)\.' "${roots[@]}" --include='*.java' 2>/dev/null \
    | grep -vFf <(patterns vt-allowlist.txt) || true)
if [ -n "$mutiny_hits" ]; then
    echo "ERROR: Mutiny/Reactor import where a virtual thread suffices (section 3.8):"
    echo "$mutiny_hits"
    fail=1
fi

if [ "$fail" -eq 0 ]; then
    echo "Concurrency guardrails: OK (no synchronized, no Mutiny/Reactor imports in engine + channels)."
fi
exit "$fail"
