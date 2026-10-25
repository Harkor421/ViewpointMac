#!/bin/zsh
# Drives Project Zomboid for ViewpointMac development without touching the player's own saves.
#   tools/game.sh start [save]   copy the save (default: the newest) to Apocalypse/vpmac-test once,
#                                launch with ZombieBuddy's local Lua server, load vpmac-test
#   tools/game.sh lua '<code>'   run Lua in the running game, print the result
#   tools/game.sh view           Viewpoint's view cycle (what O does)
#   tools/game.sh shot           screenshot to build/game/shot-N.png, prints the path
#   tools/game.sh log [n]        last n lines from Viewpoint / ViewpointMac / errors
#   tools/game.sh crash          the newest native crash report, if newer than the start
#   tools/game.sh stop           quit the game, put the player's "Continue" back
PORT=4777
APP="$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents"
ZOMBOID="$HOME/Zomboid"
LOG="$ZOMBOID/console.txt"
SAVES="$ZOMBOID/Saves/Apocalypse"
TEST=vpmac-test
OUT="${0:A:h}/../build/game"
mkdir -p "$OUT"

lua() { curl -s -m 60 -X POST "http://127.0.0.1:$PORT/lua?sandbox=false" --data-binary "$1"; }
alive() { pgrep -x JavaAppLauncher >/dev/null; }
log_has() { grep -q "$1" "$LOG" 2>/dev/null && [[ "$LOG" -nt "$OUT/started" ]]; }
wait_log() { # seconds, description, grep pattern; Lua is never called while the game loads
  local t=0
  while (( t < $1 )); do
    alive || { echo "the game exited while waiting for: $2"; return 1; }
    log_has "$3" && return 0
    sleep 2; t=$((t + 2))
  done
  echo "timed out waiting for: $2"; return 1
}

case "$1" in
  start)
    alive && { echo "the game is already running"; exit 1; }
    # A clean copy every start: a load that fails can leave the test save half written.
    if [[ ! -d "$OUT/pristine" ]]; then
      src=${2:-$(ls -t "$SAVES" | grep -v "^$TEST$" | head -1)}
      cp -R "$SAVES/$src" "$OUT/pristine" && echo "test save made from $src"
    fi
    rm -rf "$SAVES/$TEST" && cp -R "$OUT/pristine" "$SAVES/$TEST"
    # The player's Continue, unless it already points at the test save (a run that crashed
    # before stop could put it back): then the one kept from before stays.
    head -1 "$ZOMBOID/latestSave.ini" | grep -q "^$TEST" || cp "$ZOMBOID/latestSave.ini" "$OUT/latestSave.ini.player"
    touch "$OUT/started"
    # In a session of its own: stopping this script (or whatever ran it) must never take the game with it.
    (cd "$APP/Java" && /usr/bin/python3 -c 'import os, sys; os.setsid(); os.execv(sys.argv[1], sys.argv[1:])' \
      "$APP/MacOS/JavaAppLauncher" "-javaagent:ZombieBuddy.jar=experimental,lua_server_port=$PORT" -- >"$OUT/stdout.log" 2>&1 &)
    sleep 2
    pid=$(pgrep -x JavaAppLauncher)
    if [[ -n "$pid" ]]; then
      # Without a display GLFW finds no monitor and the game dies on start, so keep it awake.
      caffeinate -di -w "$pid" &
      # And never let a runaway test take the Mac with it.
      (while kill -0 "$pid" 2>/dev/null; do
        rss=$(ps -o rss= -p "$pid" | tr -d ' ')
        free=$(vm_stat | awk '/Pages free/ {f=$3} /Pages inactive/ {i=$3} END {print int((f+i)*16384/1048576)}')
        if (( ${rss:-0} > 22000000 || free < 3000 )); then
          echo "$(date +%T) memory guard: rss $((rss / 1024)) MB, $free MB free; stopping the game" >>"$OUT/guard.log"
          kill "$pid"
        fi
        sleep 1
      done) &
    fi
    exec "$0" load
    ;;
  load)
    # The menu-to-game half of start, for a game already launched (e.g. after an approval dialog).
    if grep -q 'nglfwGetVideoMode' "$OUT/stdout.log" 2>/dev/null; then
      echo "the game found no display (screen asleep or locked?)"; exit 1
    fi
    wait_log 600 "the main menu" 'STATE: exit zombie.gameStates.TermsOfServiceState' || exit 1
    # On the main menu Lua runs on the main thread, so asking is safe there (not while loading).
    t=0
    until [[ "$(lua 'return MainScreen ~= nil and MainScreen.instance ~= nil and MainScreen.instance.bottomPanel ~= nil and MainScreen.instance.sandOptions ~= nil')" == *true* ]]; do
      sleep 2; t=$((t + 2)); (( t > 120 )) && { echo "the main menu never got ready"; exit 1; }
    done
    sleep 3
    echo "loading $TEST: $(lua "MainScreen.continueLatestSave('Apocalypse', '$TEST'); return 'asked'")"
    # Past "click to start" without a click (HotReload watches for this file).
    wait_log 300 "the world to load" 'game loading took' || exit 1
    sleep 3; touch "$ZOMBOID/viewpointmac/skip-loading"
    wait_log 120 "the save to load" 'STATE: exit zombie.gameStates.GameLoadingState' || exit 1
    if grep -qE 'GameLoadingState.*Exception thrown|Cannot load world' "$LOG"; then
      echo "the save failed to load:"; grep -A1 -E 'GameLoadingState.*Exception thrown|Cannot load world' "$LOG" | head -6 | cut -c1-200
      exit 1
    fi
    sleep 12
    [[ "$(lua 'return getPlayer() ~= nil')" == *true* ]] && echo "in game" || { echo "loaded, but no player"; exit 1; }
    # ZombieBuddy's experimental log overlay (on because the Lua server needs "experimental") draws
    # every log line letter by letter: ~35,000 draws a frame with Viewpoint's slow-frame lines,
    # which halves the frame rate. A normal launch from Steam doesn't have it.
    echo "log overlay off: $(lua 'ZombieBuddy.LogOverlay.disable(); return tostring(not ZombieBuddy.LogOverlay.isEnabled())')"
    ;;
  lua) lua "$2"; echo ;;
  view) lua "Viewpoint.View.cycle(); return 'cycled'"; echo ;;
  shot)
    # Only the game's window, never the rest of the screen.
    win=$("${0:A:h}/../build/winshot" "$(pgrep -x JavaAppLauncher)")
    [[ -z "$win" ]] && { echo "no game window (in full screen there is none to capture; use dump.txt)"; exit 1; }
    n=$(ls "$OUT" | grep -c '^shot-')
    screencapture -x -o -l "$win" "$OUT/shot-$n.png" && echo "$OUT/shot-$n.png"
    ;;
  log)
    grep -nE 'ViewpointMac|\[Viewpoint\]|Exception|Error:|FATAL|^\s+at viewpoint' "$LOG" \
      | grep -vE 'has no uniform|sound files|chunk grid|Found patch|animals|root motion|SettingsTable|draw program' \
      | tail -${2:-40} | cut -c1-260
    ;;
  crash)
    crash=$(ls -t "$APP/Java/"hs_err_pid*.log 2>/dev/null | head -1)
    if [[ -n "$crash" && "$crash" -nt "$OUT/started" ]]; then
      echo "$crash"
      grep -A1 'Problematic frame' "$crash" | tail -1
      awk '/Java frames:/{f=1} f{print} /^$/{if(f)exit}' "$crash" | head -14
    else
      echo "no native crash since the start"
    fi
    ;;
  stop)
    alive && pkill -x JavaAppLauncher
    sleep 2
    [[ -f "$OUT/latestSave.ini.player" ]] && cp "$OUT/latestSave.ini.player" "$ZOMBOID/latestSave.ini"
    echo "stopped; the player's Continue is back"
    ;;
  *) sed -n '2,10p' "$0" ;;
esac
