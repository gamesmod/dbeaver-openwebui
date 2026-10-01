#!/usr/bin/env bash
# Снимает скриншоты для документации: запускает DBeaver с установленным плагином на виртуальном
# дисплее (Xvfb), подключает mock Open WebUI и проходит сценарий через xdotool.
# Используется в .github/workflows/docs.yml. Нужны: xvfb, xdotool, imagemagick, DBeaver с плагином.
#   DBEAVER_HOME=... OUT=docs/screenshots ./scripts/docs-screenshots.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${DBEAVER_HOME:?}"
OUT="${OUT:-$ROOT/docs/screenshots}"
WS="${WS:-$RUNNER_TEMP/ws}"
RAW="$OUT/raw"
mkdir -p "$OUT" "$RAW" "$WS/.metadata/.config"
cp "$ROOT/docs/demo/ai-configuration.json" "$WS/.metadata/.config/ai-configuration.json"

export DISPLAY=:99
Xvfb :99 -screen 0 1440x900x24 >/dev/null 2>&1 &
sleep 2

MOCK_DEMO=1 MOCK_DEMO_ANSWER="$ROOT/docs/demo/answer.md" python3 "$ROOT/tests/mock_owui.py" &
sleep 1

shot() {  # shot <name> — весь экран
  import -window root "$RAW/$1.png"; echo "shot $1"
}
windows() {
  echo "--- windows ($1)"; for w in $(xdotool search --onlyvisible --name '.' 2>/dev/null); do
    echo "$w $(xdotool getwindowgeometry "$w" 2>/dev/null | tr '\n' ' ') :: $(xdotool getwindowname "$w")"; done
}

"$DBEAVER_HOME/dbeaver" -nosplash -data "$WS" -vmargs -Dorg.eclipse.swt.internal.gtk.cairoGraphics=true \
  > "$RAW/dbeaver-stdout.log" 2>&1 &
DBPID=$!

for i in $(seq 1 90); do
  xdotool search --onlyvisible --name 'DBeaver' >/dev/null 2>&1 && break
  sleep 2
done
sleep 25
windows start | tee "$RAW/windows.txt"
shot 00-start

# сценарий шагов (дополняется по мере отладки)
if [ -f "$ROOT/docs/demo/scenario.sh" ]; then
  source "$ROOT/docs/demo/scenario.sh"
fi

windows end | tee -a "$RAW/windows.txt"
cp "$WS/.metadata/dbeaver-debug.log" "$RAW/" 2>/dev/null || true
kill $DBPID 2>/dev/null || true
exit 0
