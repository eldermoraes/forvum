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
#     delivered/enforced runtime boundary (spawn #177, CAPR verdict #195, web/dashboard auth #165). The
#     cost/tool budget hard-stop (#169) is ENFORCED at runtime — no doc may claim it is
#     parsed-but-not-enforced. Runtime-state owner-only permissions (#173) are ENFORCED on every boot
#     (StateDirInitializer 0700/0600) — no doc may frame the state/ umask gap as an unshipped follow-up.
#     The compression-failure fallback (#176) is BOUNDED (BoundedCompressor: truncate + fixed marker) — no
#     doc may frame it as a fail-open path that reinserts the raw text. The three-tier memory Write/Select
#     loop (#175) is WIRED into normal turns (LocalMemoryProvider default retrieval + MemoryWriter off-turn
#     write) — no doc may frame the default install as writing/reading only the short-term messages tier.
#     SBOM + signed provenance attestations SHIP on every release (#174: CycloneDX Maven+image SBOMs +
#     GitHub OIDC build-provenance for the 4 binaries + the GHCR image; policy in docs/SECURITY-GATES.md) —
#     no status-bearing doc may frame release SBOM/provenance as a named deferral (the DR-6c round file
#     keeps the pre-#174 deliberation text and, like docs/ISSUES.md, is deliberately not scanned; the SBOM
#     *attestation* follow-up lives only in docs/SECURITY-GATES.md, also unscanned).
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

# 5. The #169 budget hard-stop is enforced at runtime — no doc may claim it is parsed-but-not-enforced.
ban "budget hard-stop stated as not-yet-enforced" \
    '(budget|hard-stop)[^.|]{0,120}not (yet )?enforced at runtime'

# 6. The #173 runtime-state owner-only permission enforcement is DELIVERED — no scanned doc may frame the
#    state/ umask gap as an unshipped named follow-up (the DR-6c round file keeps the deliberation text and
#    is deliberately not scanned).
ban "state/ owner-only enforcement (#173) stated as an unshipped gap" \
    '(hardening follow-up to align .?StateDirInitializer|StateDirInitializer[^.|]{0,40}uses plain|no-.?init first boot creates .?state/[^.|]{0,80}umask)'

# 7. The #176 compression-failure fallback is BOUNDED (BoundedCompressor: truncate + fixed marker) — no
#    scanned doc may frame it as a fail-open path that reinserts the raw text at either the retrieved-memory
#    or worker-digest boundary.
ban "compression-failure fallback (#176) stated as fail-open / reinserting raw" \
    'reinserts the raw text instead of bounding|reinserting the raw text \(fail-open\)|compression-failure fallback fail-open|fail-open path tracked by #176'

# 8. The #175 three-tier memory Write/Select loop is WIRED into normal turns (LocalMemoryProvider default
#    retrieval + MemoryWriter off-turn write) — no scanned doc may frame the default install as writing/
#    reading only the short-term messages tier, or recordFact as having no production caller.
ban "three-tier memory loop (#175) stated as not-wired / recordFact unused" \
    'recordFact has no production caller|MemorySelector (only wires|wires only) Qdrant|not (yet )?wired into normal turns by default|default install[^.|]{0,40}(never|does not yet) (write|read|retriev)'

# 9. SBOM + signed provenance SHIP on every release (#174) — no status-bearing doc may frame release-side
#    SBOM/provenance as an unshipped deferral. Targets the pre-#174 phrasing (SBOM ... [signed] provenance
#    ... named deferral/deferred), NOT the legitimate as-built "SBOM attestation is the one named follow-up"
#    (that names the attest-sbom sub-item, has no "provenance" token between SBOM and the follow-up word,
#    and lives in the unscanned docs/SECURITY-GATES.md anyway).
ban "release SBOM/provenance (#174) stated as a named deferral" \
    'SBOM[^.|]{0,40}provenance[^.|]{0,50}(deferral|deferred)'

if [ "$fail" -eq 0 ]; then
    echo "docs-drift: OK (no stale status/version/coverage claims in the status-bearing docs)."
fi
exit "$fail"
