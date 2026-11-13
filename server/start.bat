@echo off
rem @NAME@ server: updates the pack, installs its NeoForge, starts the server, and restarts it for pack updates.
rem Needs Java 21 (java on the PATH, or set JAVA to its java.exe). Type "stop" to shut it down for good.
if "%JAVA%"=="" set JAVA=java
"%JAVA%" -jar tradetheory-updater.jar --server @PACK_URL@
pause
