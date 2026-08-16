#!/usr/bin/env bash
# Coverage-policy gate (#180): a jacoco exclusion or a lowered/removed coverage threshold cannot land
# without an explicit, reviewed edit to .github/coverage-allowlist.txt. Also guards the #182 mutation
# ratchet direction (thresholds may only ratchet UP).
#
# Checks:
#   1. Every <exclude> inside a module's jacoco-maven-plugin block is listed in the allowlist.
#   2. Every literal <minimum> (i.e. not the inherited ${jacoco.line.minimum}/${jacoco.branch.minimum})
#      is listed in the allowlist with owner + expiry, and is not lower than the ratified value.
#   3. The root-pom global gate has not been lowered (LINE 0.80 / BRANCH 0.75).
#   4. forvum-core's Pitest mutationThreshold/testStrengthThreshold have not dropped below the
#      committed ratchet floor (95/95).
#
# Run locally: bash .github/coverage-policy.sh
set -euo pipefail
cd "$(dirname "$0")/.."

python3 - <<'EOF'
import re
import sys
from pathlib import Path

ALLOWLIST = Path('.github/coverage-allowlist.txt')
GLOBAL_LINE, GLOBAL_BRANCH = 0.80, 0.75
MUTATION_FLOOR, STRENGTH_FLOOR = 95, 95

allowed_excludes = set()
allowed_minimums = {}
for raw in ALLOWLIST.read_text().splitlines():
    entry = raw.strip()
    if not entry or entry.startswith('#'):
        continue
    parts = entry.split('|')
    if parts[0] == 'exclude':
        allowed_excludes.add((parts[1], parts[2]))
    elif parts[0] == 'minimum':
        allowed_minimums[(parts[1], parts[2])] = float(parts[3])

errors = []

poms = sorted(Path('.').glob('*/pom.xml')) + [Path('pom.xml')]
for pom in poms:
    text = pom.read_text()
    # Scope to the jacoco plugin block(s) so enforcer bannedDependencies excludes are not matched.
    for block in re.findall(r'<artifactId>jacoco-maven-plugin</artifactId>.*?</plugin>', text, re.S):
        for exc in re.findall(r'<exclude>([^<]+)</exclude>', block):
            if (str(pom), exc) not in allowed_excludes:
                errors.append(f"{pom}: jacoco exclusion '{exc}' is not in .github/coverage-allowlist.txt "
                              f"— a new exclusion needs an explicit, reviewed allowlist entry (#180).")
        # Pair each <counter> with its <minimum> inside each <limit> (tolerating interleaved comments).
        gap = r'(?:\s|<!--(?:(?!-->).)*-->)*'
        for counter, minimum in re.findall(
                rf'<counter>(\w+)</counter>{gap}<value>COVEREDRATIO</value>{gap}<minimum>([^<]+)</minimum>',
                block, re.S):
            minimum = minimum.strip()
            if minimum.startswith('${'):
                continue  # inherited global gate
            key = (str(pom), counter)
            value = float(minimum)
            if str(pom) == 'pom.xml':
                floor = GLOBAL_LINE if counter == 'LINE' else GLOBAL_BRANCH
                if value < floor:
                    errors.append(f"pom.xml: the global {counter} gate was lowered to {value} "
                                  f"(floor {floor}) — the project-wide claim may not be weakened (#180).")
                continue
            if key not in allowed_minimums:
                errors.append(f"{pom}: literal {counter} minimum {value} is not a ratified exception in "
                              f".github/coverage-allowlist.txt (#180: lowered thresholds need owner + expiry).")
            elif value < allowed_minimums[key]:
                errors.append(f"{pom}: {counter} minimum {value} is below the ratified exception value "
                              f"{allowed_minimums[key]} — thresholds may not silently drop (#180).")

# Stale allowlist entries: an exclusion/exception whose pom no longer carries it should be pruned.
for (pom, exc) in sorted(allowed_excludes):
    text = Path(pom).read_text() if Path(pom).exists() else ''
    if f'<exclude>{exc}</exclude>' not in text:
        errors.append(f"allowlist: exclusion '{exc}' for {pom} is stale (not present in the pom) — prune it.")
for (pom, counter) in sorted(allowed_minimums):
    if not Path(pom).exists():
        errors.append(f"allowlist: {pom} no longer exists — prune its entries.")

# #182 ratchet direction: the mutation thresholds may only go up.
core = Path('forvum-core/pom.xml').read_text()
for tag, floor in (('mutationThreshold', MUTATION_FLOOR), ('testStrengthThreshold', STRENGTH_FLOOR)):
    m = re.search(rf'<{tag}>(\d+)</{tag}>', core)
    if not m:
        errors.append(f"forvum-core/pom.xml: the Pitest <{tag}> ratchet is missing (#182).")
    elif int(m.group(1)) < floor:
        errors.append(f"forvum-core/pom.xml: <{tag}> {m.group(1)} is below the committed ratchet floor "
                      f"{floor} — mutation thresholds may only ratchet up (#182). If the floor itself "
                      f"must move, update it in .github/coverage-policy.sh in maintainer review.")

if errors:
    print("coverage-policy: FAIL")
    for e in errors:
        print(f"  - {e}")
    sys.exit(1)
print("coverage-policy: OK — exclusions and thresholds match the ratified allowlist; ratchets intact.")
EOF
