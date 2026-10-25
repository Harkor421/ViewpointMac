#!/bin/zsh
# Compiles the translator and test-compiles every Viewpoint shader in a Mac legacy GL context.
# Usage: tools/check.sh [name filter]
set -e
HERE=${0:A:h}/..
GAME="$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents"
CP="$GAME/Java/projectzomboid.jar"
JDK=${JDK:-/Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin}
SHADERS=${VIEWPOINT_SHADERS:?set VIEWPOINT_SHADERS to the extracted viewpoint/shaders dir}
BUILD=${BUILD:-$HERE/build}
mkdir -p "$BUILD/tools" "$BUILD/natives"
"$JDK/javac" --release 21 -cp "$CP" -d "$BUILD/tools" "$HERE/src/vpmac/Glsl.java" "$HERE/src/vpmac/Preproc.java" "$HERE/src/vpmac/Log.java" "$HERE/tools/ShaderCheck.java"
"$GAME/PlugIns/jre-aarch64/Contents/Home/bin/java" -XstartOnFirstThread -Djava.awt.headless=true \
  --enable-native-access=ALL-UNNAMED -Dorg.lwjgl.system.SharedLibraryExtractPath="$BUILD/natives" \
  ${=JAVA_OPTS} -cp "$BUILD/tools:$CP" ShaderCheck "$SHADERS" "$HERE/tools/constants.glsl" "$BUILD/translated" "$@"
