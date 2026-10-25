#!/bin/zsh
# Shows which textures each Viewpoint program moves into the atlas, offline.
# Usage: VIEWPOINT_SHADERS=<dir> tools/packplan.sh [vert+frag,...]
set -e
HERE=${0:A:h}/..
GAME="$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents"
CP="$GAME/Java/projectzomboid.jar:$GAME/Java/ZombieBuddy.jar"
JDK=${JDK:-/Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin}
SHADERS=${VIEWPOINT_SHADERS:?set VIEWPOINT_SHADERS to the extracted viewpoint/shaders dir}
OUT=$HERE/build/tools-plan
rm -rf "$OUT"; mkdir -p "$OUT" "$HERE/build/natives"
"$JDK/javac" --release 21 -Xlint:-options -cp "$CP" -d "$OUT" "$HERE"/src/vpmac/*.java "$HERE/tools/PackPlan.java"
"$GAME/PlugIns/jre-aarch64/Contents/Home/bin/java" -XstartOnFirstThread -Djava.awt.headless=true \
  --enable-native-access=ALL-UNNAMED -Dorg.lwjgl.system.SharedLibraryExtractPath="$HERE/build/natives" \
  ${=JAVA_OPTS} -cp "$OUT:$CP" vpmac.PackPlan "$SHADERS" "$HERE/tools/constants.glsl" "$HERE/tools/runtime-defines.glsl" "$@" 2>&1 | grep -v 'ViewpointMac\] \(compiled\|program .* needs\)'
