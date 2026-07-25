#!/usr/bin/env bash
# Live-test ownership gate (#181). Every @Tag("live") test class MUST be scheduled by a workflow: the
# nightly live suite (.github/workflows/nightly-live.yml) or the per-PR native-turn job (ci.yml). A live
# test owned by no workflow is a silent coverage gap — it never runs anywhere, so a regression in the code
# it guards escapes forever. This static grep asserts each simple class name of a @Tag("live") ANNOTATION
# appears in an owning workflow (or in the explicit documented-skipped allowlist below, with a reason).
#
# It runs in the nightly `preflight` job (a per-PR hook is a follow-up once the #183 -> #180 ci.yml chain
# settles). Mirrors .github/docs-drift.sh in style; shellcheck-clean (security.yml workflow-lint gate).
set -euo pipefail
cd "$(dirname "$0")/.."

# Workflows that schedule live tests. nightly-live.yml runs the provider/browser/sandbox/websearch/tts
# suites; ci.yml's native-turn job runs the native live trio (OllamaNativeTurnIT / MemorySearchNativeIT /
# EvalLlmJudgeIT), which nightly-live.yml names in its header comment (owned-by-ci.yml, not duplicated).
workflows=".github/workflows/nightly-live.yml .github/workflows/ci.yml"

# Simple class names deliberately scheduled by NO workflow, each with a stated reason. Currently empty —
# every live test on disk is owned. Add an entry (form: "ClassName # reason") only as a named decision,
# e.g. if the websearch or tts download proves chronically flaky (D9 demotion path).
documented_skipped=()

missing_workflow=""
for wf in $workflows; do
    [ -f "$wf" ] || missing_workflow="$missing_workflow $wf"
done
if [ -n "$missing_workflow" ]; then
    echo "ERROR: live-ownership — expected workflow file(s) not found:$missing_workflow"
    exit 1
fi

fail=0
# Match only real annotation lines (`@Tag("live")` after optional indentation), never a javadoc mention
# (a ` * ... {@code @Tag("live")}` line starts with `*`, so it cannot match `^[[:space:]]*@Tag`).
while IFS=: read -r file _rest; do
    [ -n "$file" ] || continue
    class=$(basename "$file" .java)

    owned=0
    for wf in $workflows; do
        if grep -qw "$class" "$wf"; then
            owned=1
            break
        fi
    done
    if [ "$owned" -eq 1 ]; then
        continue
    fi

    if [ "${#documented_skipped[@]}" -gt 0 ]; then
        for entry in "${documented_skipped[@]}"; do
            if [ "${entry%% *}" = "$class" ]; then
                owned=1
                break
            fi
        done
    fi
    if [ "$owned" -eq 1 ]; then
        continue
    fi

    echo "ERROR: live test $class ($file) is scheduled by no workflow."
    echo "  Add it to a job in .github/workflows/nightly-live.yml (or ci.yml's native-turn job),"
    echo "  or add a documented-skipped allowlist entry with a reason in .github/live-ownership.sh."
    fail=1
done < <(grep -rlE '^[[:space:]]*@Tag\("live"\)' --include='*.java' .)

if [ "$fail" -eq 0 ]; then
    echo "live-ownership: OK (every @Tag(\"live\") class is scheduled by a workflow)."
fi
exit "$fail"
