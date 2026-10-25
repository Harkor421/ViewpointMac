#!/bin/zsh
# Compiles the mod into build/dev-classes, which the running game watches (see HotReload):
# a fix to a method body reaches the game in about a second, no restart.
# It compiles elsewhere first and hands over brand-new classes before the classes that use them,
# so the game never runs a class that points at one it does not have yet.
set -e
HERE=${0:A:h}/..
GAME="$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java"
JDK=${JDK:-/Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin}
DEV="$HERE/build/dev-classes"
STAGE="$HERE/build/dev-stage"
mkdir -p "$DEV/vpmac" "$HOME/Zomboid/viewpointmac"
print -r -- "${DEV:A}" > "$HOME/Zomboid/viewpointmac/dev.txt"
rm -rf "$STAGE"; mkdir -p "$STAGE"
"$JDK/javac" --release 21 -Xlint:-options -cp "$GAME/projectzomboid.jar:$GAME/ZombieBuddy.jar" \
  -d "$STAGE" "$HERE"/src/vpmac/*.java
added=0
for f in "$STAGE"/vpmac/*.class; do
  [[ -e "$DEV/vpmac/${f:t}" ]] && continue
  cp "$f" "$DEV/vpmac/"; added=1
done
(( added )) && sleep 3
for f in "$STAGE"/vpmac/*.class; do
  cmp -s "$f" "$DEV/vpmac/${f:t}" || cp "$f" "$DEV/vpmac/"
done
echo "compiled into build/dev-classes"
