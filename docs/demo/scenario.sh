# Шаги сценария. Функции shot/shotwin/activate/windows — из scripts/docs-screenshots.sh.
activate "Product Configuration"; xdotool key Return; sleep 3
windows after-wizard
shot 01-after-wizard
for i in 1 2 3; do  # возможные приветственные диалоги
  w=$(xdotool search --onlyvisible --name '.' | while read -r x; do n=$(xdotool getwindowname "$x"); [ "$n" != "DBeaver " ] && [ -n "$n" ] && echo "$x"; done | head -1)
  [ -z "$w" ] && break
  echo "closing dialog: $(xdotool getwindowname "$w")"; xdotool windowactivate --sync "$w"; shot "02-dialog-$i"; xdotool key Escape; sleep 3
done
wmctrl -r DBeaver -b add,maximized_vert,maximized_horz; sleep 3
activate DBeaver
shot 03-main
xdotool key alt+w; sleep 2
shot 04-window-menu
xdotool key Escape; sleep 1

# --- AI-чат: вопрос и ответ через Open WebUI
xdotool mousemove 1280 817 click 1; sleep 1
xdotool type --delay 30 "Топ-5 клиентов по выручке за последние 30 дней"; sleep 1
shot 10-chat-typed
xdotool key ctrl+Return; sleep 12
shot 11-chat-answer
windows after-chat

# --- проверка, что клики доходят до веб-страницы чата: кнопка «копировать»
clip() { xclip -o -selection clipboard -t UTF8_STRING 2>/dev/null | head -c 60; }
xdotool mousemove 1401 489; sleep 1; xdotool click 1; sleep 2
echo "clipboard after click: [$(clip)]"
xdotool mousemove 1395 485; sleep 0.5; xdotool mousemove 1401 489; sleep 0.5
xdotool mousedown 1; sleep 0.2; xdotool mouseup 1; sleep 2
echo "clipboard after down/up: [$(clip)]"
W=$(xdotool getmouselocation --shell | grep WINDOW | cut -d= -f2)
echo "window under mouse: $W $(xdotool getwindowname "$W" 2>/dev/null)"
xdotool click --window "$W" 1; sleep 2
echo "clipboard after window click: [$(clip)]"

# --- кнопка выполнения запроса из ответа
xdotool mousemove 1337 489; sleep 2
shot 13-play-hover
xdotool click 1; sleep 3
grep -i "open webui" "$WS/.metadata/dbeaver-debug.log" | tail -5
xdotool click 1; sleep 12
windows after-play
shot 14-play-result
for i in 1 2; do
  w=$(xdotool search --onlyvisible --name '.' | while read -r x; do n=$(xdotool getwindowname "$x"); case "$n" in "DBeaver"*|"") ;; *) echo "$x";; esac; done | head -1)
  [ -z "$w" ] && break
  echo "dialog after play: $(xdotool getwindowname "$w")"; shot "15-play-dialog-$i"; xdotool windowactivate --sync "$w"; xdotool key Return; sleep 8
done
shot 16-play-final

# --- список моделей с сервера
xdotool mousemove 1295 853 click 1; sleep 3
shot 12-models
xdotool key Escape; sleep 1

# --- настройки AI через шестерёнку панели чата
activate DBeaver
xdotool mousemove 1412 143 click 1; sleep 5
windows settings
wmctrl -r :ACTIVE: -e 0,150,50,1140,800; sleep 2
xdotool mousemove 700 760; sleep 2
shot 20-settings
shotwin 20-settings-win "Properties for"

# --- проверка подключения
xdotool mousemove 408 682 click 1; sleep 5
windows test-connection
shot 21-test-connection
xdotool key Return; sleep 2

# --- новый профиль: выбор движка
xdotool mousemove 1272 138 click 1; sleep 4
windows new-profile
shot 22-new-profile
xdotool mousemove 751 369 click 1; sleep 2
shot 23-engines-list
xdotool key Escape; sleep 1
xdotool key Escape; sleep 2
xdotool key Escape; sleep 2

# --- итоговые картинки
crop 11-chat-answer chat 1440x870+0+0
crop 11-chat-answer chat-panel 325x790+1115+85
crop 12-models models 325x200+1115+670
cp "$RAW/20-settings-win.png" "$OUT/settings.png"; echo "copy settings"
crop 21-test-connection test-connection 1142x826+150+50
crop 23-engines-list new-profile 372x272+540+284
