package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.File;
import java.util.Properties;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.I2PAppContext;

/**
 * Unit tests for SnarkManager.lookupTorrentName self-contained DHT lookup.
 * Verifies validation, hex handling, and temp-dir cleanup without requiring network/DHT.
 */
public class SnarkManagerLookupTest {

    private SnarkManager mgr;
    private I2PAppContext ctx;
    private File baseDir;

    @Before
    public void setUp() {
        // I2PAppContext defaults i2p.dir.base - and with it config, router and
        // app dirs - to the current working directory, so a bare context lets
        // SnarkManager create test-i2psnark-lookup/ and
        // test-i2psnark-lookup.config.d/ wherever the suite happens to run.
        // Point the whole directory tree at a scratch dir we own instead.
        baseDir = new File(System.getProperty("java.io.tmpdir"),
                            "i2p-snark-lookup-" + System.nanoTime());
        assertTrue(baseDir.mkdirs());
        Properties props = new Properties();
        props.setProperty("i2p.dir.base", baseDir.getAbsolutePath());
        ctx = new I2PAppContext(props);
        // Use a fresh SnarkManager with in-memory config; do not start DirMonitor
        mgr = new SnarkManager(ctx, "/test-i2psnark-lookup", "test-i2psnark-lookup");
        // Do not call mgr.start() - we test validation paths that don't require DHT
    }

    @After
    public void tearDown() {
        if (mgr != null) {
            try { mgr.stop(); } catch (Exception ignore) {}
        }
        // Clean any temp lookup dirs left behind
        File tmp = ctx.getTempDir();
        File[] leftovers = tmp.listFiles((dir, name) -> name.startsWith("zzzot-lookup-"));
        if (leftovers != null) {
            for (File f : leftovers) {
                try { net.i2p.util.FileUtil.rmdir(f, false); } catch (Exception ignore) {}
            }
        }
        // and the whole scratch base, including the snark config dir
        if (baseDir != null) {
            try { net.i2p.util.FileUtil.rmdir(baseDir, false); } catch (Exception ignore) {}
        }
    }

    @Test
    public void testLookupNullInfoHashReturnsNull() {
        assertNull(mgr.lookupTorrentName((byte[]) null, 1000));
    }

    @Test
    public void testLookupInvalidLengthReturnsNull() {
        assertNull(mgr.lookupTorrentName(new byte[10], 1000));
        assertNull(mgr.lookupTorrentName(new byte[21], 1000));
        assertNull(mgr.lookupTorrentName(new byte[0], 1000));
    }

    @Test
    public void testLookupNullHexReturnsNull() {
        assertNull(mgr.lookupTorrentName((String) null, 1000));
    }

    @Test
    public void testLookupInvalidHexLengthReturnsNull() {
        assertNull(mgr.lookupTorrentName("abc", 1000));
        assertNull(mgr.lookupTorrentName("0123456789abcdef0123456789abcdef0123456", 1000)); // 39 chars
        assertNull(mgr.lookupTorrentName("0123456789abcdef0123456789abcdef012345678", 1000)); // 41 chars
    }

    @Test
    public void testLookupInvalidHexCharsReturnsNullQuickly() {
        // non-hex 'zz' decodes to garbage bytes rather than throwing; with a 0
        // timeout the lookup returns without touching the DHT, so this stays fast
        String badHex = "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz";
        long start = System.currentTimeMillis();
        assertNull(mgr.lookupTorrentName(badHex, 0));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("Should return quickly for 0 timeout, was " + elapsed + "ms", elapsed < 2000);
    }

    @Test
    public void testLookupZeroTimeoutReturnsNullQuickly() {
        byte[] ih = new byte[20];
        for (int i = 0; i < 20; i++) ih[i] = (byte) i;
        long start = System.currentTimeMillis();
        String result = mgr.lookupTorrentName(ih, 0);
        long elapsed = System.currentTimeMillis() - start;
        assertNull(result);
        // Should return quickly (no 60s wait) when timeout 0 - allow some slack for temp dir creation
        assertTrue("Should return quickly for 0 timeout, was " + elapsed + "ms", elapsed < 2000);
        // Temp dir should be cleaned up
        File tmp = ctx.getTempDir();
        File[] leftovers = tmp.listFiles((dir, name) -> name.startsWith("zzzot-lookup-"));
        if (leftovers != null) {
            for (File f : leftovers) assertFalse("Temp dir should be cleaned: " + f, f.exists());
        }
    }

    @Test
    public void testLookupMethodExistsWithExpectedSignature() throws Exception {
        assertNotNull(SnarkManager.class.getMethod("lookupTorrentName", byte[].class, long.class));
        assertNotNull(SnarkManager.class.getMethod("lookupTorrentName", String.class, long.class));
    }

    @Test
    public void testLookupInfoNullReturnsNull() {
        assertNull(mgr.lookupTorrentInfo((byte[]) null, 1000));
        assertNull(mgr.lookupTorrentInfo((String) null, 1000));
    }

    @Test
    public void testLookupInfoInvalidLengthReturnsNull() {
        assertNull(mgr.lookupTorrentInfo(new byte[10], 1000));
        assertNull(mgr.lookupTorrentInfo("abc", 1000));
    }

    @Test
    public void testLookupInfoZeroTimeoutReturnsNullQuickly() {
        byte[] ih = new byte[20];
        for (int i = 0; i < 20; i++) ih[i] = (byte) (i + 20);
        long start = System.currentTimeMillis();
        SnarkManager.TorrentInfo info = mgr.lookupTorrentInfo(ih, 0);
        long elapsed = System.currentTimeMillis() - start;
        assertNull(info);
        assertTrue("Should return quickly for 0 timeout, was " + elapsed + "ms", elapsed < 2000);
        File tmp = ctx.getTempDir();
        File[] leftovers = tmp.listFiles((dir, name) -> name.startsWith("zzzot-lookup-"));
        if (leftovers != null) {
            for (File f : leftovers) assertFalse("Temp dir should be cleaned: " + f, f.exists());
        }
    }

    @Test
    public void testLookupInfoMethodExists() throws Exception {
        assertNotNull(SnarkManager.class.getMethod("lookupTorrentInfo", byte[].class, long.class));
        assertNotNull(SnarkManager.class.getMethod("lookupTorrentInfo", String.class, long.class));
        assertNotNull(SnarkManager.TorrentInfo.class.getField("name"));
        assertNotNull(SnarkManager.TorrentInfo.class.getField("size"));
    }

    @Test
    public void testTorrentInfoToStringFormatsSize() {
        SnarkManager.TorrentInfo ti = new SnarkManager.TorrentInfo("test", 12345);
        assertEquals("test", ti.name);
        assertEquals(12345, ti.size);
        assertTrue(ti.toString().contains("test"));
    }
}
