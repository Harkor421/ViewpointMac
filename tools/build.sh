#!/bin/zsh
# Builds the ViewpointMac mod into build/mod/ViewpointMac; with --install, copies it to ~/Zomboid/mods.
set -e
HERE=${0:A:h}/..
GAME="$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java"
JDK=${JDK:-/Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin}
BUILD=$HERE/build
CP="$GAME/projectzomboid.jar:$GAME/ZombieBuddy.jar"

rm -rf "$BUILD/classes" "$BUILD/mod"
mkdir -p "$BUILD/classes"
"$JDK/javac" --release 21 -Xlint:-options -cp "$CP" -d "$BUILD/classes" "$HERE"/src/vpmac/*.java

MOD="$BUILD/mod/ViewpointMac"
mkdir -p "$MOD/42/media/java/client" "$MOD/common"
cp "$HERE/mod/mod.info" "$MOD/42/mod.info"
[ -f "$HERE/mod/poster.png" ] && cp "$HERE/mod/poster.png" "$MOD/42/poster.png"
touch "$MOD/common/.keep"
"$JDK/jar" --create --file "$MOD/42/media/java/client/ViewpointMac.jar" -C "$BUILD/classes" .
echo "built $MOD"

if [[ "$1" == "--install" ]]; then
  DEST="$HOME/Zomboid/mods/ViewpointMac"
  rm -rf "$DEST"
  cp -R "$MOD" "$DEST"
  echo "installed $DEST"
fi
