# DBeaver 25.2.4: движок Open WebUI в Window → Preferences → AI. Функции shot/activate/windows — из docs-screenshots.sh.
for i in 1 2 3; do
  w=$(xdotool search --onlyvisible --name '.' | while read -r x; do n=$(xdotool getwindowname "$x"); case "$n" in "DBeaver"*|"") ;; *) echo "$x";; esac; done | head -1)
  [ -z "$w" ] && break
  echo "dialog: $(xdotool getwindowname "$w")"; xdotool windowactivate --sync "$w"; shot "01-dialog-$i"; xdotool key Return; sleep 4
done
wmctrl -r DBeaver -b add,maximized_vert,maximized_horz; sleep 3
activate DBeaver
shot 02-main
xdotool key alt+w; sleep 2
shot 03-window-menu
