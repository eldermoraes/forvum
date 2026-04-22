#!/usr/bin/env bash
set -euo pipefail

echo "=== Forvum demo launcher ==="
echo "This will validate prerequisites, build the app, and launch the interactive CLI."
echo ""

# Java check (warn, don't block — Java 25 may be active via SDKMAN without JAVA_HOME)
if ! command -v java >/dev/null 2>&1; then
  echo "WARN: 'java' not found on PATH. Forvum requires Java 25."
  echo "      If you have Java 25 via SDKMAN or similar, activate it first."
else
  JAVA_VER="$(java -version 2>&1 | head -1)"
  echo "Java: $JAVA_VER"
  if ! java -version 2>&1 | head -1 | grep -qE '"25([.+" ]|$)'; then
    echo "WARN: Java 25 is recommended. A different version was detected above."
    echo "      The build may still work, but tested target is Java 25."
  fi
fi

# Ollama check (block if unreachable)
if ! curl -sf http://localhost:11434/api/tags >/dev/null 2>&1; then
  echo "ERROR: Ollama daemon not reachable at http://localhost:11434/"
  echo "       Start it with: ollama serve"
  exit 1
fi
echo "Ollama: running"

# OLLAMA_KEEP_ALIVE hint (warn, don't block)
if [ -z "${OLLAMA_KEEP_ALIVE:-}" ]; then
  echo "HINT: OLLAMA_KEEP_ALIVE is not set. For local models, export OLLAMA_KEEP_ALIVE=30m"
  echo "      to avoid reload latency between turns. Cloud models are unaffected."
fi

echo ""
echo "=== Building forvum-app ==="
echo "Building forvum-app (first run takes ~1min as Maven resolves dependencies)..."
./mvnw package -pl forvum-app -am -DskipTests

echo ""
echo "=== Launching Forvum CLI ==="
exec java -jar forvum-app/target/quarkus-app/quarkus-run.jar
