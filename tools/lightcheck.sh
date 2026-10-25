#!/bin/zsh
# With the game in (tools/game.sh start), turns Viewpoint on and looks inside its light pass:
# GL errors, what the light program wrote, its inputs, its uniforms, and its debug views.
# Usage: tools/lightcheck.sh
HERE=${0:A:h}
LOG="$HOME/Zomboid/console.txt"
DIR="$HOME/Zomboid/viewpointmac"
ask() { print -r -- "$1" > "$DIR/dump.txt"; sleep ${2:-4}; }
mark=$(wc -l < "$LOG")
since() { tail -n +$((mark + 1)) "$LOG" | grep -E 'ViewpointMac' | grep -vE 'draw program|copied texture' | cut -c1-400; }

# Only switch Viewpoint on when the game is still isometric.
if [[ "$("$HERE/game.sh" lua 'return tostring(Viewpoint.View.next())')" != *isometric* ]]; then
  echo "view: $("$HERE/game.sh" view)"
  sleep 10
fi
ask errors 6
light=$(grep -E 'shares the atlas: \[.*uAlbedo' "$LOG" | grep -oE 'program [0-9]+' | tail -1 | cut -d' ' -f2)
[[ -z "$light" ]] && { echo "no light program found"; since | tail -30; exit 1; }
echo "light program: $light"
ask "after:$light
uniforms:$light
${light}:uOcclusion
${light}:uEngineLight
${light}:uNormal
${light}:uDepth
${light}:uAlbedo
${light}:bindings" 6
for v in 1 2 4; do
  ask "set:${light}:uDebugView=$v
screen" 4
  cp "$DIR/dumps/screen.png" "$DIR/dumps/screen-debug$v.png" 2>/dev/null
done
ask "unset:${light}
screen" 4
since
echo "memory: $(ps -o rss= -p $(pgrep -x JavaAppLauncher) | awk '{print int($1/1024) " MB"}')"
