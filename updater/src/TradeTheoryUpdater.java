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
import java.util.HexFormat;
import java.util.List;
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
 * Client: runs packwiz (mods, configs), then makes the Prism instance's NeoForge the pack's; when it had to change it,
 * it stops this launch (Prism has already loaded the old one) and asks the player to press Launch again. Offline, it
 * lets the game start with what is installed.
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
        int code = server ? server(url, bootTest) : client(url);
        System.exit(code);
    }

    // ---- players (Prism) ---------------------------------------------------------------------------------------

    private static int client(String url) throws Exception {
        Path mc = Path.of(env("INST_MC_DIR", "."));
        Path instance = Path.of(env("INST_DIR", mc.toAbsolutePath().getParent().toString()));
        if (packwiz(mc, url, "client") != 0) {
            System.out.println("[Trade Theory] Couldn't update the pack (offline?). Starting with what's installed.");
            return 0;
        }
        String neoforge = packNeoForge(url);
        if (neoforge == null) return 0;
        Path pack = instance.resolve("mmc-pack.json");
        if (!Files.exists(pack)) return 0;
        String json = Files.readString(pack);
        String updated = withNeoForge(json, neoforge);
        if (updated.equals(json)) return 0;
        Files.writeString(pack, updated);
        System.out.println("[Trade Theory] The pack now uses NeoForge " + neoforge + ". The instance was switched to it:"
                + " press Launch again to start the game.");
        return 1;   // a failing pre-launch command stops this launch: Prism already loaded the old NeoForge
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
            if (packwiz(dir, url, "server") != 0) {
                if (bootTest) return 3;
                printManualDownloads(dir, url);
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

    /**
     * The mods the pack lists as download-by-hand (manual.json next to pack.toml) that this server's mods folder lacks:
     * their authors only allow downloads through CurseForge's own app, so packwiz can't fetch them.
     */
    private static void printManualDownloads(Path dir, String url) {
        try {
            String json = fetch(url.substring(0, url.lastIndexOf('/') + 1) + "manual.json");
            Matcher entry = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"filename\"\\s*:\\s*\"([^\"]*)\"[^}]*?\"url\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
            boolean any = false;
            while (entry.find()) {
                if (Files.exists(dir.resolve("mods").resolve(entry.group(2)))) continue;
                if (!any) System.out.println("[Trade Theory] These mods must be downloaded by hand (their authors allow CurseForge's app only):");
                any = true;
                System.out.println("  " + entry.group(1) + ": open " + entry.group(3) + ", download " + entry.group(2)
                        + " and put it in " + dir.resolve("mods") + ", then start again.");
            }
        } catch (Exception ignored) {
            // offline: the error above says enough
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

    /** Runs packwiz-installer-bootstrap (next to this jar) in {@code dir} for {@code side}; its exit code. */
    private static int packwiz(Path dir, String url, String side) throws Exception {
        Path bootstrap = jarDir().resolve("packwiz-installer-bootstrap.jar");
        List<String> command = new ArrayList<>(List.of(java(), "-jar", bootstrap.toString(), "-s", side));
        if (side.equals("server") || System.getenv("TRADETHEORY_NO_GUI") != null) command.add("-g");
        command.add(url);
        return new ProcessBuilder(command).directory(dir.toFile()).inheritIO().start().waitFor();
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

    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString();
    }

    private static Path jarDir() throws Exception {
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
