#!/bin/sh
# Publishes a new Trade Theory: builds the mod's jar, puts it in extra/files/, commits and pushes. The workflow then
# rebuilds the pack and Cloudflare Pages publishes it; players get it at their next launch, the server within ~15 min.
#   scripts/publish-mod.sh [path to the mod's repository]   (default: ../chunkshop; set JAVA_HOME to a JDK 21)
set -e
cd "$(dirname "$0")/.."
MOD="${1:-../chunkshop}"
(cd "$MOD" && ./gradlew -q jar)
JAR=$(ls "$MOD"/build/libs/tradetheory-*.jar | grep -v -e sources -e javadoc | head -1)
VERSION=$(basename "$JAR" .jar | sed 's/^tradetheory-//')
git rm -q --ignore-unmatch extra/files/tradetheory-*.jar
cp "$JAR" extra/files/
git add extra/files
git commit -m "Trade Theory $VERSION"
git push
echo "Pushed Trade Theory $VERSION: the pack rebuilds and publishes by itself."
