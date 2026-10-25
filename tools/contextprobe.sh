#!/bin/zsh
# Runs tools/ContextProbe.java: Viewpoint's world pass in the game's legacy context, or in a 4.1
# core one with PROBE_OPTS=-Dcore=1, to compare what each command costs the driver.
set -e
HERE=${0:A:h}/..
GAME="$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents"
CP="$GAME/Java/projectzomboid.jar"
JDK=${JDK:-/Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin}
mkdir -p "$HERE/build/contextprobe" "$HERE/build/natives"
"$JDK/javac" --release 21 -cp "$CP" -d "$HERE/build/contextprobe" "$HERE/tools/ContextProbe.java"
"$GAME/PlugIns/jre-aarch64/Contents/Home/bin/java" -XstartOnFirstThread -Djava.awt.headless=true \
  --enable-native-access=ALL-UNNAMED -Dorg.lwjgl.system.SharedLibraryExtractPath="$HERE/build/natives" \
  ${=PROBE_OPTS} -cp "$HERE/build/contextprobe:$CP" ContextProbe
