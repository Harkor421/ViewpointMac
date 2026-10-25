#!/bin/zsh
# Every offline check of the layer, without opening the game; exits 1 if any fails.
#   1. the mod compiles
#   2. tools/SmokeTest: the stand-ins drawn in a hidden Mac legacy context, pixels checked
#   3. every shader pack (Viewpoint's and installed ones) compiles and links, through the atlas
#      where the game would use it
#   4. every installed Workshop add-on built on Viewpoint: its shader files compile, Remap rewrites
#      its classes, and no GL call it keeps is missing on the Mac
#   5. Viewpoint itself through Remap, the same way (its Windows-only upscaler interop and its
#      capability-guarded debug output allowed)
#   6. tools/LayerBench: the layer's CPU cost per frame, reported
# Usage: tools/test.sh
set -u
HERE=${0:A:h}/..
GAME="$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents"
CP="$GAME/Java/projectzomboid.jar:$GAME/Java/ZombieBuddy.jar"
JAVA="$GAME/PlugIns/jre-aarch64/Contents/Home/bin/java"
JDK=${JDK:-/Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin}
WORKSHOP="$HOME/Library/Application Support/Steam/steamapps/workshop/content/108600"
OUT=$HERE/build/test
rm -rf "$OUT" && mkdir -p "$OUT" "$HERE/build/natives"
failed=()
step() { print "\n== $1"; }
fail() { failed+=("$1"); print "FAILED: $1"; }
inGl() { "$JAVA" -XstartOnFirstThread -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED \
  -Dorg.lwjgl.system.SharedLibraryExtractPath="$HERE/build/natives" "$@"; }

step "1. compile"
if "$JDK/javac" --release 21 -Xlint:-options -cp "$CP" -d "$OUT/classes" "$HERE"/src/vpmac/*.java \
     "$HERE/tools/SmokeTest.java" "$HERE/tools/RemapCheck.java" 2>&1; then
  print "ok"
else
  fail "compile"; print "\nFAILED: ${failed[*]}"; exit 1
fi

step "2. smoke test"
inGl -cp "$OUT/classes:$CP" vpmac.SmokeTest > "$OUT/smoke.log" 2>&1
grep -E '^FAIL|^   got' "$OUT/smoke.log"
print "$(grep -c '^ok' "$OUT/smoke.log") checks passed"
grep -q '^SMOKE OK' "$OUT/smoke.log" || fail "smoke test"

step "3. shader packs"
VPJAR="$WORKSHOP/3809306528/mods/Viewpoint/42/media/java/client/Viewpoint.jar"
if [[ ! -f "$VPJAR" ]]; then
  fail "Viewpoint.jar not found at $VPJAR"
else
  unzip -qo "$VPJAR" -d "$OUT/viewpoint"
  VP="$OUT/viewpoint/viewpoint"
  packdir() { # where a pack lives: bundled in Viewpoint, or installed
    [[ -d "$VP/shaderpacks/$1" ]] && { print "$VP/shaderpacks/$1"; return; }
    [[ -d "$HOME/Zomboid/viewpoint-shaderpacks/$1" ]] && print "$HOME/Zomboid/viewpoint-shaderpacks/$1"
  }
  overlay() { # base shaders, then each pack of the chain from the root up; CHAIN lists the pack folders
    local dir=$(packdir "$1") base
    [[ -z "$dir" ]] && return
    base=$(grep -E '^base=' "$dir/pack.properties" 2>/dev/null | cut -d= -f2 | tr -d ' \r')
    [[ -n "$base" ]] && overlay "$base" "$2"
    [[ -d "$dir/shaders" ]] && cp -R "$dir/shaders/." "$2/"
    CHAIN="$CHAIN:$dir"
  }
  packs=(${(f)"$(ls "$VP/shaderpacks"; ls "$HOME/Zomboid/viewpoint-shaderpacks" 2>/dev/null)"})
  for pack in ${(u)packs}; do
    merged="$OUT/packs/$pack"
    CHAIN=""
    mkdir -p "$merged" && cp -R "$VP/shaders/." "$merged/" && overlay "$pack" "$merged"
    # The defines the game makes: those captured from it, with this pack's own options at their defaults.
    result=$(JAVA_OPTS="-Ddefines=$HERE/tools/runtime-defines.glsl -Dpacks=${CHAIN#:}" BUILD="$OUT/check-$pack" \
      VIEWPOINT_SHADERS="$merged" "$HERE/tools/check.sh" 2>&1)
    print -r -- "$result" > "$OUT/pack-$pack.log"
    summary="$(print -r -- "$result" | grep -E '^(compiled|linked)' | tr '\n' ' ')"
    print "$pack: $summary"
    print -r -- "$result" | grep -A3 -E '^=== (FAIL|LINK FAIL)'
    [[ "$summary" == *" 0 failed"*" 0 failed"* ]] || fail "shader pack $pack"
  done
fi

step "4. Workshop add-ons built on Viewpoint"
find "$WORKSHOP" -maxdepth 5 -name mod.info -print0 2>/dev/null | xargs -0 grep -l -E '^require=.*\\Viewpoint([,[:space:]]|$)' 2>/dev/null \
  | while read -r info; do print -r -- "${info:h}"; done | sort -u > "$OUT/addon-dirs.txt"
typeset -A seen
while read -r dir; do
  id=$(grep -E '^id=' "$dir/mod.info" | head -1 | cut -d= -f2 | tr -d ' \r')
  [[ -z "$id" || -n "${seen[$id]:-}" ]] && continue
  seen[$id]=1
  jars=(${(f)"$(find "${dir:h}" -name '*.jar' -path '*media/java*' 2>/dev/null)"})
  (( ${#jars} == 0 )) && continue
  x="$OUT/addons/$id"; mkdir -p "$x"
  for j in $jars; do unzip -qo "$j" -d "$x"; done
  # Its shader files, folder by folder.
  for sdir in ${(u)${(f)"$(find "$x" \( -name '*.vert' -o -name '*.frag' \) -exec dirname {} \; 2>/dev/null)"}}; do
    [[ -z "$sdir" ]] && continue
    result=$(BUILD="$OUT/check-addon-$id" VIEWPOINT_SHADERS="$sdir" "$HERE/tools/check.sh" 2>&1)
    summary="$(print -r -- "$result" | grep -E '^(compiled|linked)' | tr '\n' ' ')"
    print "$id shaders (${sdir#$x/}): $summary"
    [[ "$summary" == *" 0 failed"*" 0 failed"* ]] || { print -r -- "$result" | grep -A3 -E '^=== (FAIL|LINK FAIL)'; fail "$id shaders"; }
  done
  result=$(inGl -Dcontext -cp "$OUT/classes:$CP" vpmac.RemapCheck "$x" "$OUT/remapped/$id" 2>&1)
  print "$id: $(print -r -- "$result" | grep -E 'classes,')"
  print -r -- "$result" | grep 'MISSING'
  print -r -- "$result" | grep -q 'every GL call left exists' || fail "$id GL calls"
done < "$OUT/addon-dirs.txt"

step "5. Viewpoint through Remap"
if [[ -d "$OUT/viewpoint/viewpoint" ]]; then
  result=$(inGl -Dcontext -Dallow=EXTSemaphore,EXTMemoryObject,GL43.glDebugMessageCallback -cp "$OUT/classes:$CP" \
    vpmac.RemapCheck "$OUT/viewpoint" "$OUT/remapped/Viewpoint" 2>&1)
  print -r -- "$result" | grep -E 'classes,|MISSING|every GL call|ModelBatches|MODEL BATCHES'
  print -r -- "$result" | grep -q 'every GL call left exists' || fail "Viewpoint GL calls"
  print -r -- "$result" | grep -q 'ModelBatches.slot reads ModelSlots.count' || fail "ModelBatches patch"
fi

step "6. layer cost per frame (tools/LayerBench)"
"$HERE/tools/bench.sh" "" 300 2>&1 | tail -1

print ""
if (( ${#failed} )); then print "FAILED: ${(j:, :)failed}"; exit 1; fi
print "ALL OFFLINE CHECKS PASSED"
