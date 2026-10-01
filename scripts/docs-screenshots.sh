#!/usr/bin/env bash
# Снимает скриншоты для документации: запускает DBeaver с установленным плагином на виртуальном
# дисплее (Xvfb), подключает mock Open WebUI и проходит сценарий через xdotool.
# Используется в .github/workflows/docs.yml. Нужны: xvfb, xdotool, imagemagick, DBeaver с плагином.
#   DBEAVER_HOME=... OUT=docs/screenshots ./scripts/docs-screenshots.sh
set -uo pipefail
# Адрес из docs/demo/ai-configuration.json: openwebui.local → 127.0.0.1
grep -q openwebui.local /etc/hosts || echo '127.0.0.1 openwebui.local' | sudo tee -a /etc/hosts >/dev/null
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${DBEAVER_HOME:?}"
OUT="${OUT:-$ROOT/docs/screenshots}"
WS="${WS:-$RUNNER_TEMP/ws}"
RAW="$OUT/raw"
mkdir -p "$OUT" "$RAW" "$WS/.metadata/.config"
AI_CONFIG="${AI_CONFIG:-$ROOT/docs/demo/ai-configuration.json}"
[ "$AI_CONFIG" != "none" ] && cp "$AI_CONFIG" "$WS/.metadata/.config/ai-configuration.json"

# Демо-база SQLite и подключение к ней в проекте General
DB="$WS/shop.db"
python3 "$ROOT/docs/demo/make-db.py" "$DB"
mkdir -p "$WS/General/.dbeaver"
cat > "$WS/General/.project" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<projectDescription><name>General</name><comment></comment><projects></projects><buildSpec></buildSpec>
<natures><nature>org.jkiss.dbeaver.DBeaverNature</nature></natures></projectDescription>
XML
cat > "$WS/General/.dbeaver/data-sources.json" <<JSON
{
  "folders": {},
  "connections": {
    "sqlite-shop": {
      "provider": "sqlite",
      "driver": "sqlite_jdbc",
      "name": "Магазин (SQLite)",
      "save-password": true,
      "configuration": {
        "database": "$DB",
        "url": "jdbc:sqlite:$DB",
        "configurationType": "MANUAL",
        "type": "dev",
        "auth-model": "native"
      }
    }
  }
}
JSON

export DISPLAY=:99
Xvfb :99 -screen 0 1440x900x24 >/dev/null 2>&1 &
sleep 2
openbox >/dev/null 2>&1 &   # оконный менеджер: без него не работает фокус клавиатуры
sleep 1

MOCK_PORT=3000 MOCK_DEMO=1 MOCK_DEMO_ANSWER="$ROOT/docs/demo/answer-sqlite.md" python3 "$ROOT/tests/mock_owui.py" &
sleep 1

crop() {  # crop <исходный кадр> <итоговое имя> <геометрия WxH+X+Y> — итоговая картинка для README
  convert "$RAW/$1.png" -crop "$3" +repage "$OUT/$2.png" && echo "crop $2"
}
shot() {  # shot <name> — весь экран
  import -window root "$RAW/$1.png"; echo "shot $1"
}
activate() {  # activate <заголовок окна> — вывести окно на передний план и дать фокус
  local w; w=$(xdotool search --onlyvisible --name "$1" 2>/dev/null | tail -1)
  [ -n "$w" ] && xdotool windowactivate --sync "$w" 2>/dev/null; sleep 0.5; echo "activate $1 -> ${w:-none}"
}
shotwin() {  # shotwin <имя> <заголовок> — снимок одного окна (с рамкой)
  local w; w=$(xdotool search --onlyvisible --name "$2" 2>/dev/null | tail -1)
  if [ -n "$w" ]; then import -window "$w" -frame "$RAW/$1.png"; echo "shotwin $1"; else shot "$1"; fi
}
windows() {
  echo "--- windows ($1)"; for w in $(xdotool search --onlyvisible --name '.' 2>/dev/null); do
    echo "$w $(xdotool getwindowgeometry "$w" 2>/dev/null | tr '\n' ' ') :: $(xdotool getwindowname "$w")"; done
}

# Песочница WebKitGTK на раннерах Ubuntu 24.04 мешает мосту JS → Java (BrowserFunction) в чате
export WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS=1 WEBKIT_DISABLE_COMPOSITING_MODE=1
"$DBEAVER_HOME/dbeaver" -nosplash -data "$WS" -vmargs -Dorg.eclipse.swt.internal.gtk.cairoGraphics=true -Ddbeaver.openwebui.selftest="$WS/selftest.sql" \
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
SCENARIO="${SCENARIO:-$ROOT/docs/demo/scenario.sh}"
if [ -f "$SCENARIO" ]; then
  ( source "$SCENARIO" ) 2>&1 | tee "$RAW/scenario.log"
fi

windows end | tee -a "$RAW/windows.txt"
cp "$WS/.metadata/dbeaver-debug.log" "$RAW/" 2>/dev/null || true
kill $DBPID 2>/dev/null || true
exit 0
