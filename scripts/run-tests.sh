#!/usr/bin/env bash
# Офлайн-тесты движка: код плагина компилируется против заглушек API DBeaver (tests/api-stubs,
# сигнатуры взяты из исходников DBeaver 26.2) и гоняется против mock-сервера Open WebUI.
# Нужны JDK 21+, python3 и gson — берётся из поставки DBeaver или из GSON_JAR.
#   DBEAVER_HOME=/opt/dbeaver ./scripts/run-tests.sh
#   GSON_JAR=/path/gson-2.11+.jar ./scripts/run-tests.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -z "${GSON_JAR:-}" ]; then
  : "${DBEAVER_HOME:?Укажите DBEAVER_HOME или GSON_JAR}"
  GSON_JAR="$(ls "$DBEAVER_HOME"/plugins/com.google.gson_*.jar | sort -V | tail -1)"
fi
OUT="$ROOT/target/tests"
rm -rf "$OUT" && mkdir -p "$OUT"
javac -encoding UTF-8 --release 21 -proc:none -nowarn -cp "$GSON_JAR" -d "$OUT" \
  $(find "$ROOT/tests/api-stubs" "$ROOT/bundles/dbeaver.openwebui.ai/src" "$ROOT/tests/src" -name '*.java' \
      ! -name 'ChatExecuteFix.java')
# ChatExecuteFix завязан на SQL-редактор и AI-чат DBeaver: он компилируется в build-offline.sh
# против настоящих jar и проверяется в workflow docs

PORT=18080
python3 "$ROOT/tests/mock_owui.py" & PID=$!
trap 'kill $PID 2>/dev/null || true' EXIT
for _ in $(seq 1 50); do
  python3 -c "import socket; socket.create_connection(('127.0.0.1', $PORT), 0.2)" 2>/dev/null && break
  sleep 0.2
done
java -cp "$OUT:$GSON_JAR" dbeaver.openwebui.model.HarnessTest
