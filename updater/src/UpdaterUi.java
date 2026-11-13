import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * The few windows a player sees, in the system's own look: the mods to download by hand (picked up from the Downloads
 * folder by themselves, like Prism's own dialog for CurseForge packs), download progress, and messages. Everything
 * also goes to Prism's log.
 */
final class UpdaterUi {
    static final String TITLE = "Craftoria + Trade Theory";

    private UpdaterUi() {}

    static boolean available() {
        return !GraphicsEnvironment.isHeadless();
    }

    static void init() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
    }

    static void message(String html, boolean error) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFrame owner = ownerFrame();
                JOptionPane.showMessageDialog(owner, "<html><body style='width:340px'>" + html + "</body></html>", TITLE,
                        error ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
                owner.dispose();
            });
        } catch (Exception ignored) {
        }
    }

    /** An invisible frame so a dialog gets a taskbar entry and comes to the front. */
    private static JFrame ownerFrame() {
        JFrame f = new JFrame(TITLE);
        f.setUndecorated(true);
        f.setLocationRelativeTo(null);
        f.setVisible(true);
        f.toFront();
        return f;
    }

    // ---- download progress ---------------------------------------------------------------------------------------

    /** A small progress window; shown only if the update is still running after a moment (nothing to do: no window). */
    static final class Progress {
        private JFrame frame;
        private JProgressBar bar;
        private JLabel line;
        private volatile boolean closed;

        Progress(String title) {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }
                if (!closed) SwingUtilities.invokeLater(() -> show(title));
            }, "progress window");
            t.setDaemon(true);
            t.start();
        }

        private void show(String title) {
            if (closed || frame != null) return;
            frame = new JFrame(TITLE);
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            JPanel p = new JPanel(new BorderLayout(0, 8));
            p.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
            JLabel heading = new JLabel(title);
            heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize2D() + 2f));
            bar = new JProgressBar();
            bar.setIndeterminate(true);
            line = new JLabel("Checking the pack...");
            line.setForeground(Color.GRAY);
            line.setPreferredSize(new java.awt.Dimension(420, line.getPreferredSize().height));
            p.add(heading, BorderLayout.NORTH);
            p.add(bar, BorderLayout.CENTER);
            p.add(line, BorderLayout.SOUTH);
            frame.setContentPane(p);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        }

        void update(int done, int total, String what) {
            SwingUtilities.invokeLater(() -> {
                if (frame == null) return;
                bar.setIndeterminate(false);
                bar.setMaximum(total);
                bar.setValue(done);
                line.setText(done + " / " + total + "   " + what);
            });
        }

        void status(String text) {
            SwingUtilities.invokeLater(() -> {
                if (line != null) line.setText(text);
            });
        }

        void close() {
            closed = true;
            SwingUtilities.invokeLater(() -> {
                if (frame != null) frame.dispose();
            });
        }
    }

    // ---- mods to download by hand --------------------------------------------------------------------------------

    /** Shows the window until every mod is in place (true) or the player cancels (false). */
    static boolean manualMods(Path root, List<TradeTheoryUpdater.ManualMod> missing) throws Exception {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> new ManualWindow(root, missing, result).open());
        return result.get();
    }

    private static final class ManualWindow {
        private final Path root;
        private final Map<TradeTheoryUpdater.ManualMod, JLabel> status = new LinkedHashMap<>();
        private final List<TradeTheoryUpdater.ManualMod> waiting;
        private final CompletableFuture<Boolean> result;
        private final Path downloads = downloadsFolder();
        private final Map<String, String> hashes = new HashMap<>();
        private final ScheduledExecutorService scanner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "downloads watch");
            t.setDaemon(true);
            return t;
        });
        private JFrame frame;

        ManualWindow(Path root, List<TradeTheoryUpdater.ManualMod> missing, CompletableFuture<Boolean> result) {
            this.root = root;
            this.waiting = new ArrayList<>(missing);
            this.result = result;
        }

        void open() {
            frame = new JFrame(TITLE);
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    finish(false);
                }
            });

            JPanel content = new JPanel(new BorderLayout(0, 14));
            content.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));

            int n = waiting.size();
            JLabel heading = new JLabel(n == 1 ? "1 mod to download by hand" : n + " mods to download by hand");
            heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize2D() + 3f));
            JLabel intro = new JLabel("<html><body style='width:400px'>Their authors allow downloads only from CurseForge's"
                    + " website, so the pack can't fetch them for you. Open each page and click <b>Download</b>: the file"
                    + " is picked up from your Downloads folder by itself. This is needed once per version.</body></html>");
            JPanel top = new JPanel(new BorderLayout(0, 6));
            top.add(heading, BorderLayout.NORTH);
            top.add(intro, BorderLayout.CENTER);
            content.add(top, BorderLayout.NORTH);

            JPanel rows = new JPanel(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(5, 0, 5, 12);
            c.anchor = GridBagConstraints.WEST;
            int y = 0;
            for (TradeTheoryUpdater.ManualMod mod : waiting) {
                JLabel name = new JLabel("<html><b>" + escape(mod.name()) + "</b><br><span style='color:gray'>"
                        + escape(mod.filename()) + "</span></html>");
                JLabel state = new JLabel("Waiting for the download");
                state.setForeground(Color.GRAY);
                status.put(mod, state);
                JButton page = new JButton("Open download page");
                page.addActionListener(e -> browse(mod.url()));
                c.gridy = y++;
                c.gridx = 0;
                c.weightx = 1;
                c.fill = GridBagConstraints.HORIZONTAL;
                rows.add(name, c);
                c.gridx = 1;
                c.weightx = 0;
                c.fill = GridBagConstraints.NONE;
                rows.add(state, c);
                c.gridx = 2;
                c.insets = new Insets(5, 0, 5, 0);
                rows.add(page, c);
                c.insets = new Insets(5, 0, 5, 12);
            }
            content.add(rows, BorderLayout.CENTER);

            JLabel watching = new JLabel("<html><span style='color:gray'>Watching " + escape(downloads.toString()) + "</span></html>");
            JButton choose = new JButton("Choose files...");
            choose.addActionListener(e -> chooseFiles());
            JButton cancel = new JButton("Cancel launch");
            cancel.addActionListener(e -> finish(false));
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.add(choose);
            buttons.add(cancel);
            JPanel bottom = new JPanel(new BorderLayout(12, 0));
            bottom.add(watching, BorderLayout.CENTER);
            bottom.add(buttons, BorderLayout.EAST);
            content.add(bottom, BorderLayout.SOUTH);

            frame.setContentPane(content);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            frame.toFront();
            frame.requestFocus();
            scanner.scheduleWithFixedDelay(this::scan, 0, 1, TimeUnit.SECONDS);
        }

        /** Looks for the files: already in place, or any jar in Downloads with the right content (a renamed one too). */
        private void scan() {
            try {
                for (TradeTheoryUpdater.ManualMod mod : List.copyOf(waiting)) {
                    if (TradeTheoryUpdater.hasFile(root, mod)) found(mod);
                }
                if (waiting.isEmpty() || !Files.isDirectory(downloads)) return;
                List<Path> jars;
                try (Stream<Path> s = Files.list(downloads)) {
                    jars = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                            .filter(Files::isRegularFile).toList();
                }
                for (Path jar : jars) {
                    String sha1 = sha1Cached(jar);
                    if (sha1 == null) continue;
                    for (TradeTheoryUpdater.ManualMod mod : List.copyOf(waiting)) {
                        if (sha1.equalsIgnoreCase(mod.sha1())) {
                            place(jar, mod, true);
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("[Trade Theory] Watching Downloads: " + e);
            }
        }

        private String sha1Cached(Path file) {
            try {
                String key = file + "|" + Files.size(file) + "|" + Files.getLastModifiedTime(file).toMillis();
                if (Files.size(file) == 0) return null;   // a download still starting
                return hashes.computeIfAbsent(key, k -> {
                    try {
                        return TradeTheoryUpdater.sha1(file);
                    } catch (IOException e) {
                        return null;   // still being written: next time
                    }
                });
            } catch (IOException e) {
                return null;
            }
        }

        private void place(Path from, TradeTheoryUpdater.ManualMod mod, boolean move) throws IOException {
            Path to = TradeTheoryUpdater.target(root, mod);
            Files.createDirectories(to.getParent());
            if (move) {
                try {
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
            System.out.println("[Trade Theory] Got " + mod.filename());
            found(mod);
        }

        private void found(TradeTheoryUpdater.ManualMod mod) {
            if (!waiting.remove(mod)) return;
            SwingUtilities.invokeLater(() -> {
                JLabel state = status.get(mod);
                state.setText("✔ Got it");
                state.setForeground(new Color(0x2E7D32));
            });
            if (waiting.isEmpty()) {
                scanner.schedule(() -> finish(true), 800, TimeUnit.MILLISECONDS);
            }
        }

        private void chooseFiles() {
            JFileChooser chooser = new JFileChooser(downloads.toFile());
            chooser.setMultiSelectionEnabled(true);
            chooser.setFileFilter(new FileNameExtensionFilter("Mod files (*.jar)", "jar"));
            if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
            List<String> wrong = new ArrayList<>();
            for (java.io.File f : chooser.getSelectedFiles()) {
                try {
                    String sha1 = TradeTheoryUpdater.sha1(f.toPath());
                    TradeTheoryUpdater.ManualMod match = waiting.stream().filter(m -> sha1.equalsIgnoreCase(m.sha1())).findFirst().orElse(null);
                    if (match == null) wrong.add(f.getName());
                    else place(f.toPath(), match, false);
                } catch (IOException e) {
                    wrong.add(f.getName() + " (" + e.getMessage() + ")");
                }
            }
            if (!wrong.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "<html><body style='width:320px'>Not one of the files the pack needs: "
                        + escape(String.join(", ", wrong)) + ".<br><br>Download exactly the version each page shows.</body></html>",
                        TITLE, JOptionPane.WARNING_MESSAGE);
            }
        }

        private void finish(boolean ok) {
            scanner.shutdownNow();
            SwingUtilities.invokeLater(() -> {
                if (frame != null) frame.dispose();
            });
            result.complete(ok);
        }

        private void browse(String url) {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI.create(url));
                    return;
                }
                if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
                    new ProcessBuilder("xdg-open", url).start();
                    return;
                }
            } catch (Exception ignored) {
            }
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
            JOptionPane.showMessageDialog(frame, "Couldn't open your browser. The link was copied: paste it into your browser.\n" + url,
                    TITLE, JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /** The player's Downloads folder (Windows: wherever it was moved to; TRADETHEORY_DOWNLOADS overrides it). */
    static Path downloadsFolder() {
        String override = System.getenv("TRADETHEORY_DOWNLOADS");
        if (override != null && !override.isBlank()) return Path.of(override);
        Path home = Path.of(System.getProperty("user.home"));
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                Process p = new ProcessBuilder("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders",
                        "/v", "{374DE290-123F-4565-9164-39C4925E467B}").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes());
                p.waitFor(5, TimeUnit.SECONDS);
                for (String line : out.split("\\R")) {
                    int i = line.indexOf("REG_SZ");
                    if (i >= 0) {
                        Path dir = Path.of(line.substring(i + "REG_SZ".length()).trim());
                        if (Files.isDirectory(dir)) return dir;
                    }
                }
            } else if (!os.contains("mac")) {
                Process p = new ProcessBuilder("xdg-user-dir", "DOWNLOAD").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes()).trim();
                p.waitFor(5, TimeUnit.SECONDS);
                if (!out.isEmpty() && Files.isDirectory(Path.of(out))) return Path.of(out);
            }
        } catch (Exception ignored) {
        }
        return home.resolve("Downloads");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
