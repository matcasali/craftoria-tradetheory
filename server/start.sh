#!/bin/sh
# @NAME@ server: updates the pack, installs its NeoForge, starts the server, and restarts it for pack updates.
# Needs Java 21 (java on the PATH, or JAVA=/path/to/java). Type "stop" to shut it down for good.
cd "$(dirname "$0")"
exec "${JAVA:-java}" -jar tradetheory-updater.jar --server @PACK_URL@
