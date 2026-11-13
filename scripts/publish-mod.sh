#!/bin/sh
# Publishes a new Trade Theory: builds the mod's jar, puts it in extra/files/, commits and pushes. The workflow then
# checks and releases the pack: the server updates itself within ~15 min, players with Prism's Update Pack.
# The jar is built from the mod's working copy as it is (uncommitted changes included); raise mod_version first.
#   scripts/publish-mod.sh [path to the mod's repository]   (default: ../chunkshop; set JAVA_HOME to a JDK 21)
set -e
cd "$(dirname "$0")/.."
MOD="${1:-../chunkshop}"
(cd "$MOD" && ./gradlew -q jar)
# the jar of the version gradle.properties names (build/libs keeps older ones too)
VERSION=$(sed -n 's/^mod_version=//p' "$MOD/gradle.properties" | tr -d '\r')
JAR="$MOD/build/libs/tradetheory-$VERSION.jar"
[ -f "$JAR" ] || { echo "No $JAR: did the build fail?" >&2; exit 1; }
git rm -q --ignore-unmatch extra/files/tradetheory-*.jar
mkdir -p extra/files   # git rm takes the folder away with its only jar
cp "$JAR" extra/files/
git add extra/files
git commit -m "Trade Theory $VERSION"
git push
echo "Pushed Trade Theory $VERSION: the pack rebuilds and publishes by itself."
