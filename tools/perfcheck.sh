#!/bin/zsh
# With the game in and Viewpoint drawing (tools/game.sh start, then view), measures it in one go:
# frames per second, what the layer does per second, per-pass GPU times and a stack profile.
# Usage: tools/perfcheck.sh [seconds per measurement, default 10]
HERE=${0:A:h}
LOG="$HOME/Zomboid/console.txt"
DIR="$HOME/Zomboid/viewpointmac"
S=${1:-10}
ask() { print -r -- "$1" > "$DIR/dump.txt"; }
fps() { # average of one reading a second
  local sum=0 n=0 v
  for i in {1..$S}; do
    v=$("$HERE/game.sh" lua 'return getAverageFPS()' | tr -dc '0-9')
    [[ -n "$v" ]] && { sum=$((sum + v)); n=$((n + 1)); }
    sleep 1
  done
  (( n > 0 )) && echo $((sum / n)) || echo "?"
}
mark=$(wc -l < "$LOG")

echo "fps (layer idle): $(fps)"
ask "stats:$S"
echo "fps (counting):   $(fps)"
sleep 2
ask "gpu:$S"
echo "fps (GPU timers): $(fps)"
sleep 3
ask "profile:$S"
sleep $((S + 3))
tail -n +$((mark + 1)) "$LOG" | grep -E 'ViewpointMac\] (per second|Viewpoint.s GPU|the layer.s own)|slow frame on the render' | tail -6 | cut -c1-1500
echo "profile: $DIR/profile.txt"
sed -n '/== threads/,/== self/p' "$DIR/profile.txt" | head -8
echo "memory: $(ps -o rss= -p $(pgrep -x JavaAppLauncher) | awk '{print int($1/1024) " MB"}')"
