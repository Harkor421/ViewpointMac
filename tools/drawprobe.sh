#!/bin/zsh
# Runs tools/DrawProbe.java in a hidden Mac legacy context: what one command of an indirect
# draw list costs the driver, issued each way the layer could issue it.
set -e
HERE=${0:A:h}/..
GAME="$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents"
CP="$GAME/Java/projectzomboid.jar"
JDK=${JDK:-/Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin}
mkdir -p "$HERE/build/drawprobe" "$HERE/build/natives"
"$JDK/javac" --release 21 -cp "$CP" -d "$HERE/build/drawprobe" "$HERE/tools/DrawProbe.java"
"$GAME/PlugIns/jre-aarch64/Contents/Home/bin/java" -XstartOnFirstThread -Djava.awt.headless=true \
  --enable-native-access=ALL-UNNAMED -Dorg.lwjgl.system.SharedLibraryExtractPath="$HERE/build/natives" \
  ${=PROBE_OPTS} -cp "$HERE/build/drawprobe:$CP" DrawProbe
