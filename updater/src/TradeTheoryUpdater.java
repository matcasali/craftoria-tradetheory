import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps a Craftoria + Trade Theory install up to date from the pack's address.
 *
 * <pre>
 * Prism pre-launch:  "$INST_JAVA" -jar "$INST_MC_DIR/tradetheory-updater.jar" https://.../pack.toml
 * Server:            java -jar tradetheory-updater.jar --server https://.../pack.toml
 * CI boot test:      java -jar tradetheory-updater.jar --server --boot-test https://.../pack.toml
 * </pre>
 *
 * Client: first the mods only CurseForge's site may hand out (manual.json next to pack.toml): a window asks for the
 * missing ones and picks them up from the Downloads folder (UpdaterUi). Then packwiz, without its own window (a small
 * progress window of ours while it works; its output goes to Prism's log), then the Prism instance's NeoForge becomes
 * the pack's; when it had to change it, it stops this launch (Prism has already loaded the old one) and asks the
 * player to press Launch again. Offline, it lets the game start with what is installed (if anything is).
 * <p>
 * Hand-downloaded files of an older pack version are deleted (packwiz leaves them: the game would load both).
 * <p>
 * Server: updates the same way (server-side files only), installs the pack's NeoForge server when it changes, starts
 * the server and checks the pack every few minutes; when it changes, it warns the players, stops the server, updates
 * and starts it again. A {@code stop} typed in the console ends it for good.
 */
public final class TradeTheoryUpdater {
    private static final Pattern NEOFORGE = Pattern.compile("(?m)^\\s*neoforge\\s*=\\s*\"([^\"]+)\"");
    private static final Duration CHECK_EVERY = Duration.ofMinutes(10);
    private static final Duration WARNING = Duration.ofMinutes(5);
    /** The running server's console; the one console reader writes to whichever server is running now. */
    private static volatile OutputStream console;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public static void main(String[] args) throws Exception {
        boolean server = false, bootTest = false;
        String url = null;
        for (String a : args) {
            if (a.equals("--server")) server = true;
            else if (a.equals("--boot-test")) bootTest = true;
            else url = a;
        }
        if (url == null) {
            System.err.println("usage: tradetheory-updater [--server [--boot-test]] <pack.toml URL>");
            System.exit(2);
        }
        if (!bootTest && System.getenv("TRADETHEORY_UPDATER_CHILD") == null) {
            Path home = server ? Path.of(".") : Path.of(env("INST_MC_DIR", "."));
            Integer code = runNewestUpdater(url, args, home.toAbsolutePath().normalize());
            if (code != null) System.exit(code);
        }
        int code = server ? server(url, bootTest) : client(url);
        System.exit(code);
    }

    /**
     * This jar sits in each instance and server folder and nothing replaces it, so it updates itself: when the site
     * publishes a different updater (tradetheory-updater.sha256 next to pack.toml), that one is fetched into
     * .tradetheory/ and run in this one's place; its exit code. Null: this one is current, or the site can't be reached.
     */
    private static Integer runNewestUpdater(String url, String[] args, Path home) {
        try {
            String base = url.substring(0, url.lastIndexOf('/') + 1);
            String published = fetch(base + "tradetheory-updater.sha256").trim().split("\\s+")[0];
            Path self = Path.of(TradeTheoryUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(self) || sha256(self).equalsIgnoreCase(published)) return null;
            Path newest = home.resolve(".tradetheory").resolve("tradetheory-updater.jar");
            if (!Files.isRegularFile(newest) || !sha256(newest).equalsIgnoreCase(published)) {
                Files.createDirectories(newest.getParent());
                Path part = newest.resolveSibling("tradetheory-updater.jar.part");
                download(base + "tradetheory-updater.jar", part);
                if (!sha256(part).equalsIgnoreCase(published)) {
                    Files.deleteIfExists(part);
                    return null;   // a half-published site: this version will do
                }
                Files.move(part, newest, StandardCopyOption.REPLACE_EXISTING);
            }
            System.out.println("[Trade Theory] Running the updater's newest version (" + published.substring(0, 12) + ")");
            List<String> command = new ArrayList<>(List.of(java(), "-jar", newest.toString()));
            command.addAll(List.of(args));
            ProcessBuilder child = new ProcessBuilder(command).inheritIO();
            child.environment().put("TRADETHEORY_UPDATER_CHILD", "1");
            child.environment().put("TRADETHEORY_TOOLS", jarDir().toString());   // packwiz stays next to this jar
            return child.start().waitFor();
        } catch (Exception e) {
            return null;
        }
    }

    // ---- players (Prism) ---------------------------------------------------------------------------------------

    private static int client(String url) throws Exception {
        Path mc = Path.of(env("INST_MC_DIR", ".")).toAbsolutePath().normalize();
        Path instance = Path.of(env("INST_DIR", mc.getParent().toString()));
        boolean ui = UpdaterUi.available();
        if (ui) UpdaterUi.init();

        List<ManualMod> manual = manualMods(url, "client");
        if (manual != null) {
            removeStale(mc, manual);
            List<ManualMod> missing = manual.stream().filter(m -> !hasFile(mc, m)).toList();
            if (!missing.isEmpty()) {
                printManual(mc, missing);
                if (!ui || !UpdaterUi.manualMods(mc, missing)) {
                    System.out.println("[Trade Theory] Launch cancelled: the mods above are still missing.");
                    return 1;
                }
            }
        }

        // the instance's components as Prism loaded them for this launch: packwiz may change them (it raises the
        // loader in mmc-pack.json by itself), and so may we
        Path pack = instance.resolve("mmc-pack.json");
        String loaded = Files.exists(pack) ? Files.readString(pack) : null;

        boolean installedBefore = Files.exists(mc.resolve("packwiz.json"));
        boolean updated = packwiz(mc, url, "client") == 0;
        if (!updated && !installedBefore) {
            System.out.println("[Trade Theory] Couldn't download the pack.");
            if (ui) UpdaterUi.message("<b>Couldn't download the pack.</b><br><br>Check your internet connection and press"
                    + " Launch again. The details are in Prism's log.", true);
            return 1;
        }
        if (loaded != null) {
            String now = Files.readString(pack);
            String neoforge = updated ? packNeoForge(url) : null;
            String wanted = neoforge == null ? now : withNeoForge(now, neoforge);
            if (!wanted.equals(now)) Files.writeString(pack, wanted);
            if (!wanted.equals(loaded)) {
                String what = neoforge != null ? "NeoForge " + neoforge : "a new NeoForge";
                System.out.println("[Trade Theory] The pack now uses " + what + ". The instance was switched to it:"
                        + " press Launch again to start the game.");
                if (ui) UpdaterUi.message("<b>The pack moved to " + what + ".</b><br><br>The instance was switched to it."
                        + " Press <b>Launch</b> again to start the game.", false);
                return 1;   // a failing pre-launch command stops this launch: Prism already loaded the old components
            }
        }
        if (!updated) System.out.println("[Trade Theory] Couldn't update the pack (offline?). Starting with what's installed.");
        return 0;
    }

    /** The mmc-pack.json text with the net.neoforged component at {@code version} (unchanged if it already is). */
    static String withNeoForge(String json, String version) {
        Matcher uid = Pattern.compile("\"uid\"\\s*:\\s*\"net\\.neoforged\"").matcher(json);
        if (!uid.find()) return json;
        // the object holding it (its own nested objects, like cachedRequires, included)
        int start = uid.start(), depth = 0;
        for (; start >= 0; start--) {
            char c = json.charAt(start);
            if (c == '}') depth++;
            else if (c == '{' && depth-- == 0) break;
        }
        int end = uid.end();
        depth = 0;
        for (; end < json.length(); end++) {
            char c = json.charAt(end);
            if (c == '{') depth++;
            else if (c == '}' && depth-- == 0) break;
        }
        if (start < 0 || end >= json.length()) return json;
        String block = json.substring(start, end + 1);
        // only the object's own version fields: nested objects name versions under other keys ("equals")
        String changed = block.replaceAll("(\"(?:cachedVersion|version)\"\\s*:\\s*\")[^\"]*(\")", "$1" + Matcher.quoteReplacement(version) + "$2");
        return changed.equals(block) ? json : json.substring(0, start) + changed + json.substring(end + 1);
    }

    // ---- the server ----------------------------------------------------------------------------------------------

    private static int server(String url, boolean bootTest) throws Exception {
        Path dir = Path.of(".").toAbsolutePath().normalize();
        if (!bootTest) {
            Thread in = new Thread(() -> {   // the console still reaches the server, across restarts
                try {
                    byte[] buf = new byte[1024];
                    for (int n; (n = System.in.read(buf)) >= 0; ) {
                        OutputStream target = console;
                        if (target == null) continue;
                        try {
                            target.write(buf, 0, n);
                            target.flush();
                        } catch (IOException closed) {
                            // the server is between restarts
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "console input");
            in.setDaemon(true);
            in.start();
        }
        while (true) {
            List<ManualMod> manual = manualMods(url, "server");
            if (manual != null) {
                removeStale(dir, manual);
                List<ManualMod> missing = manual.stream().filter(m -> !hasFile(dir, m)).toList();
                if (!missing.isEmpty()) printManual(dir, missing);
            }
            if (packwiz(dir, url, "server") != 0) {
                if (bootTest) return 3;
                System.out.println("[Trade Theory] Couldn't update the pack. Starting with what's installed.");
            }
            String neoforge = packNeoForge(url);
            if (neoforge != null) installNeoForge(dir, neoforge);
            if (!Files.exists(dir.resolve(windows() ? "run.bat" : "run.sh"))) {
                System.err.println("[Trade Theory] NeoForge isn't installed here, and the pack couldn't be reached to install it.");
                return 4;
            }
            ensureMemory(dir);
            if (!eulaAccepted(dir)) {
                System.err.println("[Trade Theory] Accept the Minecraft EULA first (https://aka.ms/MinecraftEULA):"
                        + " open eula.txt here and change eula=false to eula=true.");
                return 5;
            }
            String packHash = hash(fetch(url));
            Run run = runServer(dir, url, packHash, bootTest);
            if (bootTest) return run.bootedOk ? 0 : 6;
            if (!run.updateRestart) return run.exitCode;   // stopped by hand: done
            System.out.println("[Trade Theory] Restarting with the new pack...");
        }
    }

    private record Run(int exitCode, boolean updateRestart, boolean bootedOk) {}

    private static Run runServer(Path dir, String url, String packHash, boolean bootTest) throws Exception {
        List<String> command = windows() ? List.of("cmd", "/c", "run.bat", "nogui") : List.of("sh", "run.sh", "nogui");
        Process server = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        OutputStream console = server.getOutputStream();
        TradeTheoryUpdater.console = console;
        AtomicBoolean updating = new AtomicBoolean(), booted = new AtomicBoolean();

        Thread out = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    System.out.println(line);
                    if (line.contains("Done (") && line.contains(")! For help")) {
                        booted.set(true);
                        if (bootTest) say(console, "stop");
                    }
                }
            } catch (IOException ignored) {
            }
        }, "server output");
        out.start();

        if (!bootTest) {
            Thread watch = new Thread(() -> {
                try {
                    while (server.isAlive()) {
                        Thread.sleep(CHECK_EVERY.toMillis());
                        String now;
                        try {
                            now = hash(fetch(url));
                        } catch (Exception offline) {
                            continue;
                        }
                        if (now.equals(packHash)) continue;
                        updating.set(true);
                        say(console, "say The modpack was updated: the server restarts in 5 minutes.");
                        Thread.sleep(WARNING.toMillis() - 60_000);
                        say(console, "say Restarting in 1 minute for the modpack update. Update your game before rejoining (Prism does it on launch).");
                        Thread.sleep(60_000);
                        say(console, "stop");
                        return;
                    }
                } catch (InterruptedException ignored) {
                }
            }, "pack watch");
            watch.setDaemon(true);
            watch.start();
        }

        int code = server.waitFor();
        out.join(5_000);
        return new Run(code, updating.get(), booted.get());
    }

    private static void say(OutputStream console, String command) {
        try {
            console.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            console.flush();
        } catch (IOException ignored) {
        }
    }

    // ---- mods to download by hand ------------------------------------------------------------------------------

    /** A mod whose author allows downloads only through CurseForge's site/app (manual.json next to pack.toml). */
    record ManualMod(String name, String filename, String folder, String side, String url, String sha1) {}

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern JSON_FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** The pack's download-by-hand mods for {@code side}; null when the list can't be fetched (offline). */
    static List<ManualMod> manualMods(String packUrl, String side) {
        String json;
        try {
            json = fetch(packUrl.substring(0, packUrl.lastIndexOf('/') + 1) + "manual.json");
        } catch (Exception offline) {
            return null;
        }
        List<ManualMod> mods = new ArrayList<>();
        Matcher object = JSON_OBJECT.matcher(json);
        while (object.find()) {
            Map<String, String> f = new HashMap<>();
            Matcher field = JSON_FIELD.matcher(object.group());
            while (field.find()) f.put(field.group(1), unescape(field.group(2)));
            String filename = f.getOrDefault("filename", ""), folder = f.getOrDefault("folder", "mods");
            if (filename.isEmpty() || filename.contains("/") || filename.contains("\\") || filename.startsWith(".")
                    || !folder.matches("[A-Za-z0-9_-]+")) continue;   // never a path outside the folder
            String modSide = f.getOrDefault("side", "both");
            if (!modSide.equals("both") && !modSide.equals(side)) continue;
            mods.add(new ManualMod(f.getOrDefault("name", filename), filename, folder, modSide, f.getOrDefault("url", ""),
                    f.getOrDefault("sha1", "")));
        }
        return mods;
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                b.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'u' -> {
                    b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> b.append(n);
            }
        }
        return b.toString();
    }

    static Path target(Path root, ManualMod mod) {
        return root.resolve(mod.folder()).resolve(mod.filename());
    }

    /** Whether the mod's exact file (by SHA-1) is in place. */
    static boolean hasFile(Path root, ManualMod mod) {
        Path file = target(root, mod);
        if (!Files.isRegularFile(file)) return false;
        try {
            return mod.sha1().isEmpty() || sha1(file).equalsIgnoreCase(mod.sha1());
        } catch (IOException e) {
            return false;
        }
    }

    static String sha1(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Deletes hand-downloaded files the pack no longer lists (a newer version replaced them: packwiz leaves the old
     * file, and the game would load both), remembering in .tradetheory/manual.txt which files those are.
     */
    static void removeStale(Path root, List<ManualMod> current) {
        Path record = root.resolve(".tradetheory").resolve("manual.txt");
        List<String> now = current.stream().map(m -> m.folder() + "/" + m.filename()).toList();
        try {
            if (Files.exists(record)) {
                for (String old : Files.readAllLines(record)) {
                    old = old.trim();
                    if (old.isEmpty() || now.contains(old)) continue;
                    Path file = root.resolve(old).normalize();
                    if (!file.startsWith(root) || file.getParent().equals(root)) continue;
                    if (Files.deleteIfExists(file)) System.out.println("[Trade Theory] Removed " + old + " (the pack replaced it)");
                }
            }
            Files.createDirectories(record.getParent());
            Files.write(record, now);
        } catch (IOException e) {
            System.out.println("[Trade Theory] Couldn't tidy the hand-downloaded mods: " + e.getMessage());
        }
    }

    private static void printManual(Path root, List<ManualMod> missing) {
        System.out.println("[Trade Theory] These mods must be downloaded by hand (their authors allow CurseForge only):");
        for (ManualMod m : missing) {
            System.out.println("  " + m.name() + ": open " + m.url() + ", download " + m.filename() + " and put it in "
                    + root.resolve(m.folder()));
        }
    }

    /** Installs the NeoForge server {@code version} here if it isn't the one installed (marked in .neoforge-version). */
    private static void installNeoForge(Path dir, String version) throws Exception {
        Path marker = dir.resolve(".neoforge-version");
        if (Files.exists(marker) && Files.readString(marker).trim().equals(version)
                && Files.exists(dir.resolve(windows() ? "run.bat" : "run.sh"))) return;
        System.out.println("[Trade Theory] Installing the NeoForge " + version + " server...");
        Path installer = dir.resolve("neoforge-installer.jar");
        download("https://maven.neoforged.net/releases/net/neoforged/neoforge/" + version + "/neoforge-" + version + "-installer.jar", installer);
        int code = new ProcessBuilder(java(), "-jar", installer.toString(), "--installServer").directory(dir.toFile())
                .inheritIO().start().waitFor();
        Files.deleteIfExists(installer);
        if (code != 0) throw new IOException("the NeoForge installer failed (" + code + ")");
        Files.writeString(marker, version);
    }

    /** Whether eula.txt says eula=true; a missing one is created (false), for the admin to read and accept. */
    private static boolean eulaAccepted(Path dir) throws IOException {
        Path eula = dir.resolve("eula.txt");
        if (!Files.exists(eula)) {
            Files.writeString(eula, "# By changing the setting below to TRUE you are indicating your agreement to the Minecraft EULA"
                    + " (https://aka.ms/MinecraftEULA).\neula=false\n");
        }
        return Files.readString(eula).toLowerCase(Locale.ROOT).contains("eula=true");
    }

    /** Gives the server memory if user_jvm_args.txt sets none (NeoForge's default is far too little for Craftoria). */
    private static void ensureMemory(Path dir) throws IOException {
        Path args = dir.resolve("user_jvm_args.txt");
        String text = Files.exists(args) ? Files.readString(args) : "";
        if (text.lines().anyMatch(l -> l.trim().startsWith("-Xmx"))) return;
        String memory = env("TRADETHEORY_MEMORY", "6G");
        Files.writeString(args, text + (text.isEmpty() || text.endsWith("\n") ? "" : "\n") + "-Xmx" + memory + "\n");
        System.out.println("[Trade Theory] Gave the server " + memory + " of memory (user_jvm_args.txt; change it there).");
    }

    // ---- shared --------------------------------------------------------------------------------------------------

    private static final Pattern STEP = Pattern.compile("^\\((\\d+)/(\\d+)\\) (.*)$");

    /**
     * Runs packwiz-installer-bootstrap (next to this jar) in {@code dir} for {@code side}, without its window; its exit
     * code. For a player its output goes to Prism's log and a small progress window shows while it works.
     */
    private static int packwiz(Path dir, String url, String side) throws Exception {
        Path bootstrap = jarDir().resolve("packwiz-installer-bootstrap.jar");
        List<String> command = List.of(java(), "-jar", bootstrap.toString(), "-g", "-s", side, url);
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
        if (side.equals("server") || !UpdaterUi.available()) return builder.inheritIO().start().waitFor();

        Process process = builder.redirectErrorStream(true).start();
        UpdaterUi.Progress progress = new UpdaterUi.Progress("Updating the pack");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                System.out.println(line);
                Matcher step = STEP.matcher(line.trim());
                if (step.matches()) progress.update(Integer.parseInt(step.group(1)), Integer.parseInt(step.group(2)), step.group(3));
                else if (!line.isBlank() && !Character.isWhitespace(line.charAt(0))) progress.status(line.trim());
            }
        } finally {
            progress.close();
        }
        return process.waitFor();
    }

    private static String packNeoForge(String url) {
        try {
            Matcher m = NEOFORGE.matcher(fetch(url));
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String fetch(String url) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
        return r.body();
    }

    private static void download(String url, Path to) throws Exception {
        HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
        try (InputStream in = r.body()) {
            Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString();
    }

    private static Path jarDir() throws Exception {
        String tools = System.getenv("TRADETHEORY_TOOLS");   // set when a newer updater runs from .tradetheory/
        if (tools != null && !tools.isBlank()) return Path.of(tools);
        return Path.of(TradeTheoryUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
