#!/usr/bin/env bash
# CI native-first discipline gate (CLAUDE.md section 5 / 12, ULTRAPLAN section 6). The single binary is
# the primary build target: it must stay free of constructs that break GraalVM native-image — runtime
# reflection outside framework-managed paths, dynamic class loading (outside the documented JVM-only
# `~/.forvum/plugins/` drop-in path), and the vetoed dependencies. The native build itself catches most
# of these late and slowly; this static scan over every reactor module's src/main fails the (fast) JVM
# job early with a precise file:line, mirroring .github/concurrency-guardrails.sh.
#
# Banned (each with a fixed-string allowlist; a hit whose `file:line:code` contains a listed substring
# is permitted):
#   1. sun.misc.Unsafe                         -> breaks native; no Unsafe.
#   2. net.sf.cglib / org.springframework.cglib -> runtime bytecode generation.
#   3. javassist.util.proxy / ProxyFactory     -> runtime bytecode generation.
#   4. dynamic class loading (Class.forName, ClassLoader.defineClass/loadClass, new URLClassLoader)
#      -> reflective/dynamic loading; the only sanctioned dynamic path is the JVM-fast-jar-only
#         `~/.forvum/plugins/` drop-in (PluginInstallCommand/MavenPluginResolver, guarded by
#         ImageMode.NATIVE_RUN), allowlisted in native-discipline-allowlist.txt.
#
# Comment lines (Javadoc `*`, `//`, `/*`) are excluded: the only mentions of these tokens in src/main
# today are documentation of the ban itself.
set -euo pipefail
cd "$(dirname "$0")/.."

# Every reactor module's production sources.
roots=()
for d in forvum-*/src/main; do
    [ -d "$d" ] && roots+=("$d")
done
if [ "${#roots[@]}" -eq 0 ]; then
    echo "native-discipline: no module src/main found (nothing to scan)."
    exit 0
fi

# Strip comments + blank lines from the allowlist, yielding fixed-string patterns (never empty: a lone
# sentinel keeps `grep -F` from treating an empty pattern set as "match everything").
allowlist() {
    { grep -vE '^[[:space:]]*(#|$)' native-discipline-allowlist.txt 2>/dev/null || true; \
      printf '\0__never_matches__\n'; }
}

# Drop Java comment lines (`:<n>:   * ...`, `// ...`, `/* ...`) so the ban does not flag documentation.
strip_comments() {
    grep -vE ':[0-9]+:[[:space:]]*(\*|//|/\*)'
}

fail=0
scan() {
    local label="$1" pattern="$2"
    local hits
    hits=$(grep -rnE "$pattern" "${roots[@]}" --include='*.java' 2>/dev/null \
        | strip_comments \
        | grep -vFf <(allowlist) || true)
    if [ -n "$hits" ]; then
        echo "ERROR: $label (breaks the native binary — CLAUDE.md section 5/12):"
        echo "$hits"
        fail=1
    fi
}

scan "sun.misc.Unsafe usage"          'sun\.misc\.Unsafe'
scan "CGLib runtime bytecode gen"     '(net\.sf\.cglib|org\.springframework\.cglib)\.'
scan "runtime Javassist proxy"        'javassist\.util\.proxy'
scan "dynamic class loading"          '\b(Class\.forName|ClassLoader\.getSystemClassLoader|\.defineClass\(|\.loadClass\(|new[[:space:]]+URLClassLoader)\b'

if [ "$fail" -eq 0 ]; then
    echo "native-discipline: OK (no Unsafe / CGLib / runtime Javassist / dynamic class loading in src/main)."
fi
exit "$fail"
