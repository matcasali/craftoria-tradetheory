@NAME@ - server kit

1. Unzip this into an empty folder.
2. Install Java 21 if the machine doesn't have it.
3. Run start.bat (Windows) or start.sh (Linux/macOS) once. It downloads the pack's server mods and NeoForge,
   then stops and asks you to accept the Minecraft EULA: read https://aka.ms/MinecraftEULA, and if you agree,
   change eula=false to eula=true in eula.txt.
4. Memory: 6 GB by default; change the -Xmx line in user_jvm_args.txt (e.g. -Xmx8G).
5. Run start again. From then on:
   - every start brings the pack up to date first;
   - while it runs, it checks the pack every 10 minutes; when it changed, it tells the players, restarts after
     5 minutes and comes back on the new version;
   - typing "stop" in the console shuts it down for good.
