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
xdotool key ctrl+Return; sleep 10
shot 11-chat-answer
windows after-chat

# --- список моделей с сервера
xdotool mousemove 1295 853 click 1; sleep 3
shot 12-models
xdotool key Escape; sleep 1

# --- настройки: Window → Preferences
activate DBeaver
xdotool key alt+w; sleep 2
xdotool mousemove 421 389 click 1; sleep 4
windows prefs
shot 20-prefs-open
xdotool type --delay 50 "Engines"; sleep 3
shot 21-prefs-filter
