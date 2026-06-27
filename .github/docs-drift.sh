#!/usr/bin/env bash
# CI documentation-drift gate (#179, CLAUDE.md section 10 "keep project docs in sync"). The status,
# version, and enforcement facts in the source-of-truth docs must agree with the running code. After a
# milestone or release closes, stale "still planned / not yet wired" prose tends to linger in one doc
# while another is updated; this static grep fails the fast JVM job early with a file:line when a known
# stale claim reappears in a status-bearing doc. It is a REGRESSION guard for a fixed set of drift
# classes, not a full semantic checker, mirroring .github/native-discipline.sh.
#
# Canonical status facts (UPDATE THESE HERE when they change — e.g. when Phase-3 ships):
#   - The M1-M20 roadmap AND Phase-2 (v0.5, OpenClaw parity) have SHIPPED, released as v0.5.0;
#     Phase-3 (v1.0+) is next. Live sequencing: docs/IMPLEMENTATION-ORDER.md.
#   - JaCoCo 80% line / 75% branch coverage gates are wired + ENFORCED in `./mvnw verify`.
#   - Known as-built gaps in shipped v0.5 are flagged inline as `as-built ... #NNN`, never stated as a
#     delivered/enforced runtime boundary (budget #169, memory #175, compression #176, spawn #177,
#     CAPR verdict #195, web/dashboard auth #165).
#
# `docs/ISSUES.md` is deliberately NOT scanned: it preserves each step's ORIGINAL proposal as historical
# text, distinguished from as-built reality by the marker legend at the top of that file.
set -euo pipefail
cd "$(dirname "$0")/.."

# The status-bearing docs the #179 audit reconciled.
docs=(README.md CLAUDE.md CONTRIBUTING.md docs/ULTRAPLAN.md docs/CONTEXT-ENGINEERING-MAPPING.md \
      docs/SCENARIO-FORMAT.md docs/IMPLEMENTATION-ORDER.md)

fail=0
ban() {
    local label="$1" pattern="$2"
    local hits
    hits=$(grep -rniE "$pattern" "${docs[@]}" 2>/dev/null || true)
    if [ -n "$hits" ]; then
        echo "ERROR: $label — a stale status claim was re-introduced (#179, CLAUDE.md section 10):"
        echo "$hits"
        echo "  Fix the doc, or (if the status genuinely changed) update the canonical facts in"
        echo "  .github/docs-drift.sh and the source-of-truth preamble in docs/ULTRAPLAN.md."
        echo
        fail=1
    fi
}

# 1. JaCoCo coverage gates are wired + enforced — no doc may say they are planned / not wired / not gated.
ban "JaCoCo coverage stated as not-yet-enforced" \
    'jacoco[^.]{0,60}(not[ -]yet[ -]wired|not[ -]yet[ -]enforced|are planned, not yet wired|not gated today)'

# 2. Phase-2 / v0.5 has shipped — no doc may frame it as the upcoming / next / planned roadmap arc.
ban "Phase-2/v0.5 framed as not-yet-shipped" \
    'phase[- ]?2 \(v0\.5[^)]*\) is the next roadmap arc'

# 3. The CE mapping (and siblings) must not claim only M1 is delivered and M2+ merely planned.
ban "docs claim only M1 shipped, M2 onward planned" \
    'M2 onward (is planned|materializes those contracts)'

# 4. The QA/scenario doc must not pin v0.1 as the current shipping version.
ban "v0.1 pinned as current shipping version" \
    'v0\.1 ships only'

if [ "$fail" -eq 0 ]; then
    echo "docs-drift: OK (no stale status/version/coverage claims in the status-bearing docs)."
fi
exit "$fail"
