#!/bin/zsh
# Saves Viewpoint's final image, without the game's interface, as a JPEG (1600 px wide).
# It reads the frame inside the game: nothing else on the screen is captured.
#   tools/screenshot.sh <file.jpg> [program]
# Without a program it asks the layer which one composites the final image (finalprog).
setopt null_glob
OUT=${1:?usage: tools/screenshot.sh <file.jpg> [program]}
D="$HOME/Zomboid/viewpointmac"
LOG="$HOME/Zomboid/console.txt"
program=$2
if [[ -z $program ]]; then
  mark=$(wc -l < "$LOG")
  print -r -- "finalprog" > "$D/dump.txt"
  for i in {1..20}; do
    line=$(tail -n +$((mark + 1)) "$LOG" | grep "draws into the game's framebuffers" | tail -1)
    [[ -n $line ]] && break
    sleep 0.5
  done
  # The composite draws once a frame: the entry with the fewest draws.
  program=$(print -r -- "$line" | grep -oE 'program [0-9]+ -> framebuffer [0-9]+=[0-9]+' | sort -t= -k2 -n | head -1 | grep -oE '^program [0-9]+' | cut -d' ' -f2)
  [[ -z $program ]] && { echo "Viewpoint drew nothing into the game's framebuffers (is a Viewpoint view on?)"; exit 1; }
fi
rm -f "$D"/dumps/after-$program-fbo*.png
print -r -- "after:$program" > "$D/dump.txt"
f=()
for i in {1..30}; do f=("$D"/dumps/after-$program-fbo*.png); (( $#f )) && break; sleep 0.5; done
(( $#f )) || { echo "no frame from program $program"; exit 1; }
# Written as it goes: wait until its size holds still.
prev=-1
while :; do size=$(stat -f %z "$f[1]"); (( size == prev && size > 0 )) && break; prev=$size; sleep 0.7; done
sips -s format jpeg -s formatOptions 85 -Z 1600 "$f[1]" --out "$OUT" >/dev/null && echo "$OUT (program $program, $(du -k "$OUT" | cut -f1) KB)"
