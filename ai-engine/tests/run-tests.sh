#!/usr/bin/env bash
# Offline tests: compiles the plugin against API stubs (copied/mirrored from DBeaver 26.2 sources)
# and runs the engine against a mock Open WebUI server. Needs JDK 21+, python3 and a gson jar.
#   GSON_JAR=/path/to/gson-2.11+.jar ./tests/run-tests.sh
set -euo pipefail
cd "$(dirname "$0")"
GSON_JAR="${GSON_JAR:?Set GSON_JAR to gson 2.11+ jar (e.g. from DBeaver plugins/com.google.gson_*.jar)}"
OUT=$(mktemp -d)
javac -nowarn -proc:none -d "$OUT" -cp "$GSON_JAR" $(find api-stubs ../src src -name '*.java')
python3 mock_owui.py & PID=$!
trap 'kill $PID 2>/dev/null || true' EXIT
sleep 1
java -cp "$OUT:$GSON_JAR" dbeaver.openwebui.model.HarnessTest
