package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.File;
import java.util.Properties;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.I2PAppContext;

/**
 * Unit tests for SnarkManager.shouldQueueMessage, the duplicate-suppression
 * decision behind addMessage/addMessageNoEscape. The check and the state
 * update must be atomic and second-scoped: the same text twice in one second
 * is suppressed, the same text in a later second is queued again, and a
 * different text always queues.
 *
 * @since 0.9.72+
 */
public class SnarkManagerMessageDedupeTest {

    private SnarkManager mgr;
    private File baseDir;

    @Before
    public void setUp() {
        // Mirror SnarkManagerLookupTest: point i2p.dir.base at a scratch dir
        // so the manager's config dirs never touch the workspace.
        baseDir = new File(System.getProperty("java.io.tmpdir"),
                            "i2p-snark-dedupe-" + System.nanoTime());
        assertTrue(baseDir.mkdirs());
        Properties props = new Properties();
        props.setProperty("i2p.dir.base", baseDir.getAbsolutePath());
        I2PAppContext ctx = new I2PAppContext(props);
        mgr = new SnarkManager(ctx, "/test-i2psnark-dedupe", "test-i2psnark-dedupe");
    }

    @After
    public void tearDown() {
        if (mgr != null) {
            try { mgr.stop(); } catch (Exception ignore) {}
        }
        if (baseDir != null) {
            try { net.i2p.util.FileUtil.rmdir(baseDir, false); } catch (Exception ignore) {}
        }
    }

    @Test
    public void testFirstMessageQueued() {
        assertTrue(mgr.shouldQueueMessage(1000L, "hello"));
    }

    @Test
    public void testSameTextSameSecondSuppressed() {
        assertTrue(mgr.shouldQueueMessage(1000L, "hello"));
        assertFalse(mgr.shouldQueueMessage(1000L, "hello"));
        assertFalse(mgr.shouldQueueMessage(1000L, "hello"));
    }

    @Test
    public void testSameTextLaterSecondQueued() {
        assertTrue(mgr.shouldQueueMessage(1000L, "hello"));
        assertFalse(mgr.shouldQueueMessage(1000L, "hello"));
        assertTrue(mgr.shouldQueueMessage(1001L, "hello"));
    }

    @Test
    public void testDifferentTextAlwaysQueued() {
        assertTrue(mgr.shouldQueueMessage(1000L, "hello"));
        assertTrue(mgr.shouldQueueMessage(1000L, "world"));
        assertTrue(mgr.shouldQueueMessage(1000L, "hello"));
    }

    @Test
    public void testEarlierSecondRequeues() {
        // the timestamp is compared, not monotonic-gated: a clock step back
        // re-queues the same text rather than suppressing it forever
        assertTrue(mgr.shouldQueueMessage(1001L, "hello"));
        assertTrue(mgr.shouldQueueMessage(1000L, "hello"));
    }
}
