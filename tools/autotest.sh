#!/bin/zsh
# Tests ViewpointMac in the real game, hands off: starts Project Zomboid with ZombieBuddy's local
# Lua server, continues the latest save, turns Viewpoint on, waits, takes a screenshot, prints what
# Viewpoint and ViewpointMac logged, and quits the game.
# Usage: tools/autotest.sh [seconds in first person, default 25]
HOLD=${1:-25}
PORT=4777
APP="$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents"
LOG="$HOME/Zomboid/console.txt"
SHOTS="$HOME/Zomboid/Screenshots"
OUT="${0:A:h}/../build/autotest"
mkdir -p "$OUT"

lua() { curl -s -m 30 -X POST "http://127.0.0.1:$PORT/lua?sandbox=false" --data-binary "$1"; }
alive() { pgrep -x JavaAppLauncher >/dev/null; }
wait_for() { # seconds, description, lua expression that returns true when ready
  local t=0
  while (( t < $1 )); do
    alive || { echo "game exited while waiting for: $2"; return 1; }
    [[ "$(lua "return $3" 2>/dev/null)" == *true* ]] && return 0
    sleep 3; t=$((t + 3))
  done
  echo "timed out waiting for: $2"; return 1
}
finish() {
  echo "---- log (Viewpoint, ViewpointMac, errors) ----"
  grep -nE 'ViewpointMac|\[Viewpoint\]|Exception|Error:|FATAL' "$LOG" \
    | grep -vE 'has no uniform|sound files|chunk grid|Found patch|animals|root motion|SettingsTable' | tail -60 | cut -c1-260
  local crash=$(ls -t "$APP/Java/"hs_err_pid*.log 2>/dev/null | head -1)
  if [[ -n "$crash" && "$crash" -nt "$OUT/started" ]]; then
    echo "---- native crash: $crash ----"
    grep -A1 'Problematic frame' "$crash" | tail -1
    awk '/Java frames:/{f=1} f{print} /^$/{if(f)exit}' "$crash" | head -12
  fi
  alive && pkill -x JavaAppLauncher
  echo "done"
}

alive && { echo "the game is already running; close it first"; exit 1; }
touch "$OUT/started"
echo "starting the game"
(cd "$APP/Java" && "$APP/MacOS/JavaAppLauncher" "-javaagent:ZombieBuddy.jar=experimental,lua_server_port=$PORT" -- >/dev/null 2>&1 &)

t=0
until curl -s -m 2 "http://127.0.0.1:$PORT/status" >/dev/null 2>&1; do
  sleep 3; t=$((t + 3))
  (( t > 240 )) && { echo "the Lua server never came up"; finish; exit 1; }
done
echo "Lua server up after ${t}s"

wait_for 240 "main menu" 'MainScreen ~= nil and MainScreen.instance ~= nil' || { finish; exit 1; }
echo "continuing: $(lua 'local s = getLatestSave(); MainScreen.continueLatestSave(s[2], s[1]); return s[1] .. " (" .. s[2] .. ")"')"
wait_for 300 "the save to load" 'getPlayer() ~= nil and getGameTime() ~= nil' || { finish; exit 1; }
sleep 12
echo "in game; Viewpoint's next view: $(lua 'return Viewpoint.View.next()')"
echo "cycle: $(lua 'Viewpoint.View.cycle(); return "asked"')"

t=0
while (( t < HOLD )); do
  alive || { echo "the game closed ${t}s after turning Viewpoint on"; finish; exit 1; }
  sleep 3; t=$((t + 3))
done
before=$(ls "$SHOTS" 2>/dev/null | wc -l)
echo "(no in-game screenshot: TakeScreenshot from Lua aborts the JVM)"
sleep 4
shot=$(ls -t "$SHOTS"/* 2>/dev/null | head -1)
[[ -n "$shot" ]] && cp "$shot" "$OUT/last.png" && echo "screenshot saved: $OUT/last.png"
finish
