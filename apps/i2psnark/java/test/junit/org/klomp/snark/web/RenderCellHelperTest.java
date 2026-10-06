package org.klomp.snark.web;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for the two per-row render helpers extracted from I2PSnarkServlet:
 * the status-cell peer count and the file-icon suffix lookup.
 *
 * <p>The peer count used to be built once up front and shared by every status
 * branch, but four branches emit their own peer-count cell and discarded it.
 * The suffix icon used to be found by scanning every registered suffix with
 * endsWith. Both tests therefore compare against the markup the former
 * implementations produced, since these strings are user-visible.
 *
 * @since 0.9.71+
 */
public class RenderCellHelperTest {

    /** The single concatenation buildStatusString used to hoist and share. */
    private static String refPeerCountHtml(int curPeers, int knownPeers, boolean noThinsp) {
        String thinsp = noThinsp ? " / " : ("&thinsp;/&thinsp;");
        return "</td><td class=peerCount><b><span class=right>" + curPeers + "</span>"
               + thinsp + "<span class=left>" + knownPeers + "</span>";
    }

    /** The linear endsWith scan toIcon used to run over every registered suffix. */
    private static String refSuffixScan(String lowerPath, Map<String, String> suffixes) {
        for (Map.Entry<String, String> entry : suffixes.entrySet()) {
            if (lowerPath.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> suffixIconMap() throws Exception {
        Class<?> iconMaps = Class.forName("org.klomp.snark.web.I2PSnarkServlet$IconMaps");
        Field field = iconMaps.getDeclaredField("SUFFIX_ICON_MAP");
        field.setAccessible(true);
        return (Map<String, String>) field.get(null);
    }

    // ----- status-cell peer count -----

    @Test
    public void peerCountMatchesFormerSharedString() {
        int[] counts = {0, 1, 7, 42, 99, 1000, Integer.MAX_VALUE};
        for (int cur : counts) {
            for (int known : counts) {
                for (boolean noThinsp : new boolean[]{false, true}) {
                    assertEquals(refPeerCountHtml(cur, known, noThinsp),
                                 I2PSnarkServlet.peerCountHtml(cur, known, noThinsp));
                }
            }
        }
    }

    @Test
    public void peerCountSeparatesCurrentFromKnownPeers() {
        assertTrue(I2PSnarkServlet.peerCountHtml(3, 9, false).contains("<span class=right>3</span>"));
        assertTrue(I2PSnarkServlet.peerCountHtml(3, 9, false).contains("<span class=left>9</span>"));
        assertTrue(I2PSnarkServlet.peerCountHtml(3, 9, false).startsWith("</td><td class=peerCount><b>"));
    }

    @Test
    public void peerCountThinSpaceDefaultAndDegradedFallback() {
        assertTrue(I2PSnarkServlet.peerCountHtml(3, 9, false).contains("&thinsp;/&thinsp;"));
        assertTrue(I2PSnarkServlet.peerCountHtml(3, 9, true).contains(" / "));
    }

    // ----- file-icon suffix lookup -----

    @Test
    public void suffixIconResolvesSingleExtensions() {
        assertEquals("windows", I2PSnarkServlet.suffixIcon("setup.exe"));
        assertEquals("code", I2PSnarkServlet.suffixIcon("style.css"));
        assertEquals("tar", I2PSnarkServlet.suffixIcon("dist.tgz"));
        assertEquals("font", I2PSnarkServlet.suffixIcon("face.woff2"));
        assertEquals("i2p", I2PSnarkServlet.suffixIcon("router.su3"));
    }

    /**
     * Only the last dot is consulted. This matches the old scan because no
     * registered suffix is itself suffixed by another (pinned below), so at
     * most one key can match and a match must start at the last dot.
     */
    @Test
    public void suffixIconConsultsOnlyTheLastDot() {
        // ".exe" is not the extension here, so the row gets no suffix icon
        assertNull(I2PSnarkServlet.suffixIcon("setup.exe.bak.download"));
        assertNull(I2PSnarkServlet.suffixIcon("dist.tar.gz"));
        assertEquals("code", I2PSnarkServlet.suffixIcon("archive.js.js"));
        assertEquals("windows", I2PSnarkServlet.suffixIcon("setup.bak.exe"));
        assertNull(I2PSnarkServlet.suffixIcon("setup.exe.bak"));
    }

    @Test
    public void suffixIconIsCaseInsensitiveViaLowerCasedInput() {
        // toIcon lower-cases the path before calling, so the contract is on the argument
        assertEquals("windows", I2PSnarkServlet.suffixIcon("SETUP.EXE".toLowerCase(Locale.US)));
        assertEquals("text", I2PSnarkServlet.suffixIcon("ReadMe.MD".toLowerCase(Locale.US)));
        assertEquals("html", I2PSnarkServlet.suffixIcon("Index.JSP".toLowerCase(Locale.US)));
    }

    @Test
    public void suffixIconHandlesDotEdgeCases() {
        // a dot-leading name yields itself, which the scan also matched
        assertEquals("windows", I2PSnarkServlet.suffixIcon(".exe"));
        assertEquals("windows", I2PSnarkServlet.suffixIcon("a..exe"));
        assertNull(I2PSnarkServlet.suffixIcon("noextension"));
        assertNull(I2PSnarkServlet.suffixIcon(""));
        assertNull(I2PSnarkServlet.suffixIcon("trailing."));
        // a dot in a directory component is not an extension
        assertNull(I2PSnarkServlet.suffixIcon("/dir.d/file"));
    }

    @Test
    public void noRegisteredSuffixIsItselfSuffixed() throws Exception {
        Map<String, String> suffixes = suffixIconMap();
        assertFalse("suffix map must not be empty", suffixes.isEmpty());
        for (String key : suffixes.keySet()) {
            for (String other : suffixes.keySet()) {
                if (!key.equals(other)) {
                    assertFalse(key + " must not be suffixed by " + other, key.endsWith(other));
                }
            }
        }
    }

    /**
     * The whole reason a single lookup may replace the scan: with no suffix
     * suffixed by another, at most one key can match a path, so the scan's
     * iteration order was never observable.
     */
    @Test
    public void singleLookupAgreesWithScanOverEveryRegisteredPair() throws Exception {
        Map<String, String> suffixes = suffixIconMap();
        for (String key : suffixes.keySet()) {
            for (String tail : suffixes.keySet()) {
                assertEquals("pair " + key + tail,
                             refSuffixScan(key + tail, suffixes),
                             I2PSnarkServlet.suffixIcon(key + tail));
                assertEquals("pair " + key + tail,
                             refSuffixScan("prefix" + key + tail, suffixes),
                             I2PSnarkServlet.suffixIcon("prefix" + key + tail));
            }
        }
    }

    @Test
    public void singleLookupAgreesWithScanOnCompoundRealWorldNames() throws Exception {
        Map<String, String> suffixes = suffixIconMap();
        String[] names = {
            "a.exe", "A.EXE", "Foo.Tar.GZ", "x.tar.gz", "noext", "", ".", "..", "a.", ".",
            ".bashrc", "song.MP3", "video.mp4", "doc.pdf", "book.epub", "arch.rar", "f.tar",
            "f.txz", "notes.md", "sum.md5", "n.nfo", "cfg.ini", "disk.iso", "disc.nrg",
            "bin.bin", "run.sh", "run.bat", "lib.dll", "app.appimage", "flat.flatpak",
            "snap.snap", "pkg.deb", "pkg.rpm", "book.fb2", "book.azw3", "book.prc",
            "src.c", "src.cpp", "src.h", "src.cgi", "src.php", "src.pl", "src.py", "s.srt",
            "f.url", "f.woff", "f.ttf", "xpi.xpi2p", "i2pinstall.exe", "i2p+install.exe",
            "/home/u/a.i2p/i2pinstall.exe", "/dir.d/file.exe", "/dir.exe/file", "x.exe.bak",
            "x.bak.exe", "weird.EXE.TXT", "double..exe", "trailing.exe.", "MIXED.Su3",
            "mount.iso.img", "exec.sh", "cgi-bin", "y.json", "z.jsp", "w.exe.exe",
        };
        for (String name : names) {
            String lower = name.toLowerCase(Locale.US);
            assertEquals("[" + name + "]", refSuffixScan(lower, suffixes),
                         I2PSnarkServlet.suffixIcon(lower));
        }
    }
}
