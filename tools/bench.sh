#!/bin/zsh
# Times a Viewpoint-shaped frame through the layer in a hidden Mac legacy context (tools/LayerBench).
# Usage: tools/bench.sh [classes dir of another build, to compare] [frames]
# BENCH_OPTS=-Dprofile=10 also samples the stacks for 10 s into ~/Zomboid/viewpointmac/profile.txt.
set -e
HERE=${0:A:h}/..
GAME="$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents"
CP="$GAME/Java/projectzomboid.jar:$GAME/Java/ZombieBuddy.jar"
JDK=${JDK:-/Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin}
OUT="$HERE/build/bench"
rm -rf "$OUT" && mkdir -p "$OUT/layer" "$OUT/bench" "$HERE/build/natives"
"$JDK/javac" --release 21 -Xlint:-options -cp "$CP" -d "$OUT/layer" "$HERE"/src/vpmac/*.java
"$JDK/javac" --release 21 -Xlint:-options -cp "$OUT/layer:$CP" -d "$OUT/bench" "$HERE/tools/LayerBench.java"
LAYER=${1:-$OUT/layer}
"$GAME/PlugIns/jre-aarch64/Contents/Home/bin/java" -XstartOnFirstThread -Djava.awt.headless=true \
  --enable-native-access=ALL-UNNAMED -Dorg.lwjgl.system.SharedLibraryExtractPath="$HERE/build/natives" ${=BENCH_OPTS} \
  -cp "$OUT/bench:${LAYER:A}:$CP" vpmac.LayerBench ${2:-300} 2>&1 | grep -vE '^\[ViewpointMac\] (draw program|atlas|program [0-9]+ (needs|shares|draws))'
