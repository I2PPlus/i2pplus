package org.klomp.snark.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Version helpers for the I2PSnark Bridge extension: reads the version from the
 * manifest.json inside the bundled XPI, and compares dotted extension versions
 * numerically so the router can tell whether the installed extension is current.
 */
public final class BridgeVersion {

    /** The HTTP header the extension adds to router requests to self-report. */
    public static final String HEADER = "X-I2PSnark-Bridge";

    private BridgeVersion() {}

    /**
     * Reads the "version" field from the manifest.json inside a zipped XPI.
     *
     * @param xpi the open XPI stream; closed by this method. May be null.
     * @return the version string, or null if it cannot be determined
     */
    public static String readXpiVersion(InputStream xpi) {
        if (xpi == null) {return null;}
        try (ZipInputStream zin = new ZipInputStream(xpi)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (!"manifest.json".equals(entry.getName())) {continue;}
                ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
                byte[] buf = new byte[1024];
                int r;
                while ((r = zin.read(buf)) > 0) {baos.write(buf, 0, r);}
                String json = baos.toString("UTF-8");
                return extractVersion(json);
            }
        } catch (IOException ioe) {
            return null;
        }
        return null;
    }

    /**
     * Extracts the "version" string from extension manifest JSON without a JSON
     * parser, by locating the version key and reading the quoted value after it.
     *
     * @param json the manifest content
     * @return the version, or null if not found
     */
    static String extractVersion(String json) {
        if (json == null) {return null;}
        int idx = json.indexOf("\"version\"");
        if (idx < 0) {return null;}
        int start = json.indexOf('"', idx + 9);
        if (start < 0) {return null;}
        int end = json.indexOf('"', start + 1);
        if (end < 0) {return null;}
        String version = json.substring(start + 1, end);
        return version.isEmpty() ? null : version;
    }

    /**
     * Compares two dotted version strings numerically by segment; missing or
     * non-numeric segments count as 0. "0.1.1" &gt; "0.1.0", "0.1.10" &gt;
     * "0.1.9", "1.0" &gt; "0.9.99".
     *
     * @param a first version, may be null
     * @param b second version, may be null
     * @return negative if a is older, zero if equal, positive if a is newer
     */
    public static int compare(String a, String b) {
        String[] as = split(a);
        String[] bs = split(b);
        int n = Math.max(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            int av = segment(as, i);
            int bv = segment(bs, i);
            if (av != bv) {return av < bv ? -1 : 1;}
        }
        return 0;
    }

    /**
     * Whether an update to the bundled version is available for the installed one.
     *
     * @param installed the version reported by the extension, may be null
     * @param bundled the version bundled in this war, may be null
     * @return true if installed is older than bundled
     */
    public static boolean isUpdateAvailable(String installed, String bundled) {
        return installed != null && bundled != null && compare(installed, bundled) < 0;
    }

    private static String[] split(String v) {
        if (v == null) {return new String[0];}
        return v.split("\\.");
    }

    private static int segment(String[] parts, int i) {
        if (i >= parts.length) {return 0;}
        String p = parts[i];
        int end = 0;
        while (end < p.length() && Character.isDigit(p.charAt(end))) {end++;}
        if (end == 0) {return 0;}
        try {return Integer.parseInt(p.substring(0, end));} catch (NumberFormatException nfe) {return 0;}
    }
}