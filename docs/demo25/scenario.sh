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
xdotool mousemove 421 362 click 1; sleep 5
wmctrl -r :ACTIVE: -e 0,150,50,1140,800; sleep 2
xdotool type --delay 80 "AI"; sleep 2
xdotool key Down; sleep 3
windows prefs
shot 04-prefs-ai
xdotool mousemove 205 152 click 1; sleep 4
shot 05-ai-page
xdotool mousemove 520 160 click 1; sleep 2
shot 06-engine-combo
xdotool mousemove 520 224 click 1; sleep 4
windows engine-selected
shot 07-openwebui-selected
xdotool mousemove 880 283 click 1; sleep 1
xdotool type --delay 40 "sk-test"; sleep 1
xdotool mousemove 880 224 click 3 2>/dev/null; sleep 0.5; xdotool key Escape
xdotool mousemove 880 224 click 1; sleep 0.5; xdotool key ctrl+a; xdotool type --delay 20 "http://openwebui.local:3000/api"; sleep 1
shot 08-filled
xdotool mousemove 1262 320 click 1; sleep 4
shot 09-models-refreshed
xdotool mousemove 1229 320 click 1; sleep 2
shot 10-models-list
xdotool key Down; sleep 0.5; xdotool key Return; sleep 2
xdotool mousemove 408 602 click 1; sleep 6
windows test-connection
shot 11-test-connection
