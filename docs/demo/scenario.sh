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
