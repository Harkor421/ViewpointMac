#!/bin/zsh
# Runs tools/GlProbe.java in a hidden Mac legacy context: what the context offers, and which
# readbacks and buffer writes make the CPU wait for the GPU.
set -e
HERE=${0:A:h}/..
GAME="$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents"
CP="$GAME/Java/projectzomboid.jar"
JDK=${JDK:-/Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin}
mkdir -p "$HERE/build/probe" "$HERE/build/natives"
"$JDK/javac" --release 21 -cp "$CP" -d "$HERE/build/probe" "$HERE/tools/GlProbe.java"
"$GAME/PlugIns/jre-aarch64/Contents/Home/bin/java" -XstartOnFirstThread -Djava.awt.headless=true \
  --enable-native-access=ALL-UNNAMED -Dorg.lwjgl.system.SharedLibraryExtractPath="$HERE/build/natives" \
  -cp "$HERE/build/probe:$CP" GlProbe
