#!/usr/bin/env bash
# Смоук-тесты HTTP-клиента, промптов, парсера и истории чатов против встроенного фейкового Open WebUI.
# Не требуют Eclipse: нужен только gson из поставки DBeaver.
#   DBEAVER_HOME=/opt/dbeaver ./scripts/run-tests.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${DBEAVER_HOME:?Укажите DBEAVER_HOME}"
GSON="$(ls "$DBEAVER_HOME"/plugins/com.google.gson_*.jar | head -1)"
OUT="$ROOT/target/tests"
rm -rf "$OUT" && mkdir -p "$OUT"
SRC="$ROOT/bundles/io.dbtools.openwebui/src/io/dbtools/openwebui"
javac -encoding UTF-8 --release 21 -cp "$GSON" -d "$OUT" \
  "$SRC"/api/*.java "$SRC"/ai/*.java "$SRC"/history/*.java \
  "$ROOT"/bundles/io.dbtools.openwebui.tests/src/io/dbtools/openwebui/api/*.java
java -cp "$GSON:$OUT" io.dbtools.openwebui.api.OpenWebUIClientSmokeTest
java -cp "$GSON:$OUT" io.dbtools.openwebui.api.HistorySmokeTest
