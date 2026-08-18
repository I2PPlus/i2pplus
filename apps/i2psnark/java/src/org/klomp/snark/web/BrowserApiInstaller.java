package org.klomp.snark.web;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.klomp.snark.MagnetHandler;

/**
 * Registers the browser protocol/file handler that forwards magnet links and
 * torrent files to the I2PSnark browser API (POST /i2psnark/_add).
 *
 * <p>No shell script is involved: the registered command is the router's own
 * JVM running {@link MagnetHandler} with the console URL baked in, so it keeps
 * working across console port or HTTPS changes made before installation.
 *
 * <p>Linux: writes a .desktop file and registers it in mimeapps.list, which
 * xdg-open, Firefox, and Chromium-based browsers honor.
 *
 * <p>Windows: writes per-user HKCU\Software\Classes registry entries for the
 * magnet: scheme and the .torrent file type (no admin rights needed).
 *
 * <p>macOS: not supported (LaunchServices has no CLI scheme registration).
 *
 * @since 0.9.71+
 */
public final class BrowserApiInstaller {

    public static final String DESKTOP_ID = "i2psnark-browserapi.desktop";
    private static final String MAGNET_MIME = "x-scheme-handler/magnet";
    private static final String TORRENT_MIME = "application/x-bittorrent";

    private BrowserApiInstaller() {}

    /**
     * Register the browser handler pointing at the given console base URL,
     * e.g. http://127.0.0.1:7657/i2psnark (no trailing slash).
     *
     * @param consoleBaseUrl base URL of the I2PSnark webapp
     * @return a human-readable result message
     * @throws IOException on any failure
     */
    public static String install(String consoleBaseUrl) throws IOException {
        if (consoleBaseUrl == null || consoleBaseUrl.isEmpty()) {
            throw new IOException("no console URL");
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.US);
        if (os.contains("windows")) {
            return installWindows(consoleBaseUrl);
        } else if (os.contains("mac")) {
            throw new IOException("browser handler registration is not supported on macOS");
        }
        String home = System.getProperty("user.home");
        if (home == null) {
            throw new IOException("user.home not set");
        }
        return installLinux(consoleBaseUrl, new File(home));
    }

    /**
     * Linux registration with an explicit home directory (testable).
     *
     * @param consoleBaseUrl base URL of the I2PSnark webapp
     * @param home the user home dir, e.g. System.getProperty("user.home")
     * @return a human-readable result message
     * @throws IOException on any failure
     */
    public static String installLinux(String consoleBaseUrl, File home) throws IOException {
        File appsDir = new File(home, ".local/share/applications");
        if (!appsDir.isDirectory() && !appsDir.mkdirs()) {
            throw new IOException("cannot create " + appsDir);
        }
        File desktop = new File(appsDir, DESKTOP_ID);
        String cmd = buildCommand(consoleBaseUrl, true);
        String content = "[Desktop Entry]\n"
                       + "Version=1.0\n"
                       + "Type=Application\n"
                       + "Name=I2PSnark Browser API\n"
                       + "Comment=Send magnet links and torrent files to I2PSnark\n"
                       + "Exec=" + cmd + "\n"
                       + "Terminal=false\n"
                       + "MimeType=" + MAGNET_MIME + ";" + TORRENT_MIME + ";\n"
                       + "Categories=Network;\n";
        writeFile(desktop, content);
        updateMimeapps(new File(home, ".config/mimeapps.list"), MAGNET_MIME, TORRENT_MIME);
        // best-effort: refresh the desktop database so handlers appear immediately
        try {
            Process p = new ProcessBuilder("update-desktop-database", appsDir.getAbsolutePath())
                            .redirectErrorStream(true).start();
            p.waitFor();
        } catch (Exception e) {
            // not critical
        }
        return "Browser handler installed: " + desktop.getAbsolutePath();
    }

    /**
     * The java binary that launched this router, or javaw on Windows.
     */
    private static String javaBinary() {
        String bin = System.getProperty("java.home") + File.separator + "bin" + File.separator;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.US);
        return bin + (os.contains("windows") ? "javaw.exe" : "java");
    }

    /**
     * The jar (or classes dir) that contains MagnetHandler, resolved from its
     * code source so no shell quoting or path guessing is needed.
     */
    private static String handlerClasspath() throws IOException {
        java.net.URL loc = MagnetHandler.class.getProtectionDomain().getCodeSource().getLocation();
        if (loc == null) {
            throw new IOException("cannot locate MagnetHandler classpath entry");
        }
        try {
            return new File(loc.toURI()).getAbsolutePath();
        } catch (java.net.URISyntaxException use) {
            throw new IOException("cannot locate MagnetHandler classpath entry", use);
        }
    }

    /**
     * The command template used by the OS registration, e.g.
     * "java" -cp "i2psnark.jar" org.klomp.snark.MagnetHandler --url "base" %u
     */
    private static String buildCommand(String consoleBaseUrl, boolean uriPlaceholder) throws IOException {
        StringBuilder rv = new StringBuilder(256);
        rv.append('"').append(javaBinary()).append("\" -cp \"")
           .append(handlerClasspath()).append("\" ")
           .append(MagnetHandler.class.getName())
           .append(" --url \"")
           .append(consoleBaseUrl)
           .append("\" ");
        rv.append(uriPlaceholder ? "%u" : "\"%1\"");
        return rv.toString();
    }

    //////////////// Linux ////////////////

    private static void writeFile(File f, String content) throws IOException {
        try (java.io.Writer w = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }

    /**
     * Add or update the two MIME registrations in the [Default Applications]
     * section of mimeapps.list, preserving everything else.
     */
    private static void updateMimeapps(File file, String... mimes) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        List<String> lines = new ArrayList<>();
        if (file.isFile()) {
            try (BufferedReader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    lines.add(line);
                }
            }
        }
        int section = -1;
        int sectionEnd = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.equals("[Default Applications]")) {
                section = i;
            } else if (section >= 0 && l.startsWith("[")) {
                sectionEnd = i;
                break;
            }
        }
        if (section < 0) {
            lines.add("");
            lines.add("[Default Applications]");
            section = lines.size();
            sectionEnd = lines.size();
        }
        for (String mime : mimes) {
            String prefix = mime + "=";
            int idx = -1;
            for (int i = section + 1; i < sectionEnd; i++) {
                if (lines.get(i).startsWith(prefix)) {
                    idx = i;
                    break;
                }
            }
            String entry = prefix + DESKTOP_ID;
            if (idx >= 0) {
                lines.set(idx, entry);
            } else {
                lines.add(sectionEnd, entry);
                sectionEnd++;
            }
        }
        writeFile(file, join(lines) + "\n");
    }

    private static String join(List<String> lines) {
        StringBuilder rv = new StringBuilder(1024);
        boolean first = true;
        for (String l : lines) {
            if (!first) {rv.append('\n');}
            rv.append(l);
            first = false;
        }
        return rv.toString();
    }

    //////////////// Windows ////////////////

    private static String installWindows(String consoleBaseUrl) throws IOException {
        String cmd = buildCommand(consoleBaseUrl, false);
        String torrentId = "I2PSnarkTorrent";
        String[][] regs = {
            {"HKCU\\Software\\Classes\\magnet\\shell\\open\\command", "/ve", "/d", cmd, "/f"},
            {"HKCU\\Software\\Classes\\.torrent", "/ve", "/d", torrentId, "/f"},
            {"HKCU\\Software\\Classes\\" + torrentId + "\\shell\\open\\command", "/ve", "/d", cmd, "/f"},
        };
        int n = 0;
        for (String[] args : regs) {
            List<String> full = new ArrayList<>(args.length + 1);
            full.add("reg");
            full.add("add");
            for (String a : args) {full.add(a);}
            try {
                Process p = new ProcessBuilder(full).redirectErrorStream(true).start();
                StringBuilder out = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {out.append(line).append('\n');}
                }
                if (p.waitFor() != 0) {
                    throw new IOException("reg add failed: " + out.toString().trim());
                }
                n++;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("reg add interrupted", ie);
            }
        }
        return "Browser handler installed (" + n + " registry entries)";
    }
}
