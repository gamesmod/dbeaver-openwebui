#!/usr/bin/env bash
# Сквозная проверка надстройки «фоновые чаты» в настоящем DBeaver (только CI):
# DBeaver с плагином, надстройкой и тестовым бандлом tests/async-uitest запускается дважды
# на виртуальном дисплее, mock Open WebUI отвечает фоновыми задачами.
#   DBEAVER_HOME=... OUT=... ./scripts/ui-async-test.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${DBEAVER_HOME:?}"
OUT="${OUT:-$RUNNER_TEMP/ui-async}"
WS="$OUT/ws"
mkdir -p "$OUT" "$WS/.metadata/.config"
grep -q openwebui.local /etc/hosts || echo '127.0.0.1 openwebui.local' | sudo tee -a /etc/hosts >/dev/null
cp "$ROOT/docs/demo/ai-configuration.json" "$WS/.metadata/.config/ai-configuration.json"

export DISPLAY=:98
Xvfb :98 -screen 0 1440x900x24 >/dev/null 2>&1 &
sleep 2
openbox >/dev/null 2>&1 &
MOCK_PORT=3000 python3 "$ROOT/tests/mock_owui.py" > "$OUT/mock.log" 2>&1 &
sleep 1
export WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS=1 WEBKIT_DISABLE_COMPOSITING_MODE=1

run_phase() {
  local phase="$1"
  echo "==> Фаза $phase"
  "$DBEAVER_HOME/dbeaver" -nosplash -data "$WS" -vmargs -Dowui.uitest.phase="$phase" -Dowui.uitest.out="$OUT/result" \
    > "$OUT/phase$phase.log" 2>&1 &
  local pid=$!
  # Мастер первого запуска (DBeaver 26.2.2+) и прочие стартовые окна закрываются Enter/Escape
  ( for _ in $(seq 1 40); do
      for w in $(xdotool search --onlyvisible --name 'Product Configuration' 2>/dev/null); do
        xdotool windowactivate --sync "$w" key Return 2>/dev/null; echo "закрыт мастер первого запуска"
      done
      for w in $(xdotool search --onlyvisible --name '.' 2>/dev/null); do
        n=$(xdotool getwindowname "$w" 2>/dev/null)
        case "$n" in ""|DBeaver*|"Product Configuration") ;;
          *) echo "закрыто окно: $n"; xdotool windowactivate --sync "$w" key Escape 2>/dev/null ;;
        esac
      done
      sleep 3
    done ) &
  local closer=$!
  for _ in $(seq 1 120); do
    [ -f "$OUT/phase$phase.json" ] && break
    kill -0 $pid 2>/dev/null || break
    sleep 2
  done
  sleep 2
  command -v import >/dev/null && import -window root "$OUT/phase$phase.png" 2>/dev/null
  kill $closer 2>/dev/null
  kill $pid 2>/dev/null; sleep 1; kill -9 $pid 2>/dev/null || true
  grep -E 'OWUI-STEP' "$OUT/phase$phase.log" || true
  grep -E '^(PASS|FAIL) |OWUI-UITEST' "$OUT/phase$phase.log" || true
  cat "$OUT/phase$phase.json" 2>/dev/null || echo "Нет результата фазы $phase"
}

run_phase 1
run_phase 2
cp "$WS/.metadata/dbeaver-debug.log" "$OUT/" 2>/dev/null || true
for p in 1 2; do
  python3 -c "import json,sys; sys.exit(0 if json.load(open('$OUT/phase$p.json')).get('ok') else 1)" 2>/dev/null \
    || { echo "::error title=ui-async-test::фаза $p не прошла"; exit 1; }
done
echo "OK"
