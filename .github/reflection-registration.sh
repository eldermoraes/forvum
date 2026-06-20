#!/usr/bin/env bash
# CI native-first reflection-registration gate (CLAUDE.md section 5 / 12, ULTRAPLAN section 6.3). Every
# JSON-(de)serialized DTO in a Quarkus-bearing module (Layer 2+) is a record that MUST carry
# `@RegisterForReflection`, or Jackson cannot bind it in the native image (the canonical constructor /
# accessors are stripped). CLAUDE.md notes this was "planned from M3+ once the SDK re-exports the
# annotation"; a NAIVE enforcer over *every* record breaks the build, because many Layer-2+ records
# legitimately carry NO annotation:
#   - forvum-core (Layer 0) records are EXEMPT — core bans io.quarkus*; their reflection is registered
#     from forvum-engine's CoreReflectionRegistration holder (so this scan skips forvum-core);
#   - config records that tree-walk a JsonNode with NO reflective Jackson binding (QdrantConfig,
#     ShellAllowlist, WebToolConfig, …) need no hint;
#   - engine value/graph records that are never serialized (GraphTurnRequest, Delivery, replay/doctor
#     view records) need no hint.
#
# The reliable, low-false-positive scope is therefore SERIALIZATION packages: a record declared in a
# `.dto.` package of a Quarkus-bearing module is, by the project's own convention, a wire DTO and MUST
# carry the annotation. The current reactor passes this 100% (every `…/dto/*.java` record is annotated),
# so the gate enforces the convention without flagging a single compliant-by-other-means class.
#
# A full Maven-enforcer form (fail-the-build on any un-hinted serialized record, module-wide) is
# DEFERRED: distinguishing a reflectively-bound record from a JsonNode-tree-walk one needs type
# analysis a grep cannot do, and would force false-positive suppressions onto every config record. This
# scoped grep is the conservative, green-today gate; widen the scope only when a new serialization
# package convention is adopted.
set -euo pipefail
cd "$(dirname "$0")/.."

# Every Quarkus-bearing module's production sources EXCEPT forvum-core (Layer-0, annotation-exempt) and
# forvum-sdk (Layer-1, Quarkus-free; it re-exports the annotation but ships no DTOs).
roots=()
for d in forvum-*/src/main; do
    case "$d" in
        forvum-core/*|forvum-sdk/*) continue ;;
    esac
    [ -d "$d" ] && roots+=("$d")
done
if [ "${#roots[@]}" -eq 0 ]; then
    echo "reflection-registration: no Quarkus-bearing module src/main found (nothing to scan)."
    exit 0
fi

# Wire DTOs live in a `.dto.` package: `…/<module>/dto/Foo.java`. (portable: no `mapfile` — macOS bash 3.2)
scanned=0
fail=0
missing=""
while IFS= read -r f; do
    [ -n "$f" ] || continue
    scanned=$((scanned + 1))
    # Only files declaring a record (skip a `package-info.java` or a dto-package interface/enum).
    grep -qE '\brecord\b' "$f" || continue
    grep -q 'RegisterForReflection' "$f" || missing="$missing  $f"$'\n'
done < <(find "${roots[@]}" -type f -name '*.java' -path '*/dto/*' 2>/dev/null | sort)

if [ -n "$missing" ]; then
    echo "ERROR: DTO record(s) in a Quarkus-bearing module missing @RegisterForReflection (native"
    echo "       Jackson binding will fail — CLAUDE.md section 5/6.3):"
    printf '%s' "$missing"
    fail=1
fi

if [ "$fail" -eq 0 ]; then
    echo "reflection-registration: OK ($scanned DTO file(s) scanned; every record carries @RegisterForReflection)."
fi
exit "$fail"
