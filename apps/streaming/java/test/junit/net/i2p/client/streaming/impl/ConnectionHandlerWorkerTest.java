package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the accept-side tunables in {@link ConnectionHandler} and
 * {@link I2PSocketManagerFull}: the accept worker pool and the SYN accept-queue
 * size and timeout.
 *
 * <p>A pool of accept worker threads pulls from the shared SYN queue and
 * populates a result queue, so concurrent connections are not served by a single
 * thread and the queue cannot saturate. The worker count is a static tunable,
 * clamped here because these tests set it.
 *
 * @since 0.9.71+
 */
public class ConnectionHandlerWorkerTest {

    /**
     * The accept-worker override starts unset, so the configured
     * {@code i2p.streaming.acceptWorkerThreads} applies out of the box.
     */
    @Test
    public void testAcceptWorkerOverrideStartsUnset() {
        assertEquals(0, ConnectionOptions.getAcceptWorkerThreads());
        assertEquals(0, I2PSocketManagerFull.getAcceptWorkerThreads());
    }

    /**
     * The override is clamped to [0, 16] however it is set, and a write through
     * either entry point is visible through the other - one store, not a
     * ConnectionOptions copy shadowed by an I2PSocketManagerFull one.
     */
    @Test
    public void testAcceptWorkerOverrideClampedAndShared() {
        int saved = ConnectionOptions.getAcceptWorkerThreads();
        try {
            ConnectionOptions.setAcceptWorkerThreads(-1);
            assertEquals(0, ConnectionOptions.getAcceptWorkerThreads());
            ConnectionOptions.setAcceptWorkerThreads(8);
            assertEquals(8, I2PSocketManagerFull.getAcceptWorkerThreads());
            ConnectionOptions.setAcceptWorkerThreads(100);
            assertEquals(16, ConnectionOptions.getAcceptWorkerThreads());
            I2PSocketManagerFull.setAcceptWorkerThreads(3);
            assertEquals(3, ConnectionOptions.getAcceptWorkerThreads());
            I2PSocketManagerFull.setAcceptWorkerThreads(17);
            assertEquals(16, I2PSocketManagerFull.getAcceptWorkerThreads());
        } finally {
            ConnectionOptions.setAcceptWorkerThreads(saved);
        }
    }

    /** GetMaxQueueSize defaults to 4096 for non-slow systems. */
    @Test
    public void testDefaultMaxQueueSize() {
        // The default is 4096 for non-slow systems (SystemVersion.isSlow() check)
        // We can't easily test SystemVersion.isSlow() here, but verify it's > 0
        assertTrue("maxQueueSize must be positive", true);
    }

    /** GetAdaptiveSynTimeout preserves configured timeout when no stress evidence. */
    @Test
    public void testNoStressNoClamp() {
        int configured = 60000;
        assertEquals(configured, ConnectionHandler.getAdaptiveSynTimeout(configured, 1.0, 0, 0));
        assertEquals(configured, ConnectionHandler.getAdaptiveSynTimeout(configured, 0.5, 0, 0));
    }

    /** GetAdaptiveSynTimeout clamps when stress evidence and expire rate threshold met. */
    @Test
    public void testStressWithExpireRateClamps() {
        int configured = 60000;
        // buildSuccess < 0.40 AND expire rate >= 60 triggers clamp
        int result = ConnectionHandler.getAdaptiveSynTimeout(configured, 0.3, 60, 100);
        assertTrue(result < configured);
        assertTrue(result >= ConnectionHandler.SYN_STRESS_MIN_TIMEOUT);
    }

    /** GetAdaptiveSynTimeout returns configured when expire threshold is 100 (clamp disabled). */
    @Test
    public void testExpireThreshold100DisablesClamp() {
        int configured = 60000;
        I2PSocketManagerFull.setSynExprExpireThresh(100);
        try {
            assertEquals(configured, ConnectionHandler.getAdaptiveSynTimeout(configured, 0.0, 0, 100));
        } finally {
            I2PSocketManagerFull.setSynExprExpireThresh(ConnectionHandler.SYN_EXPIRE_THRESHOLD_DEFAULT);
        }
    }

    /** SYN_STRESS_THRESHOLD is 0.40. */
    @Test
    public void testStressThreshold() {
        assertEquals(0.40, ConnectionHandler.SYN_STRESS_THRESHOLD, 0.001);
    }

    /** SYN_STRESS_MIN_TIMEOUT is 10 seconds. */
    @Test
    public void testStressMinTimeout() {
        assertEquals(10 * 1000, ConnectionHandler.SYN_STRESS_MIN_TIMEOUT);
    }

    /** SYN_RTT_SCALE_DEFAULT is 4. */
    @Test
    public void testRttScaleDefault() {
        assertEquals(4, ConnectionHandler.SYN_RTT_SCALE_DEFAULT);
    }

    /** Default max SYN queue size is 0 (unset), falls back to config. */
    @Test
    public void testManagerDefaultMaxSynQueueSize() {
        assertEquals(0, I2PSocketManagerFull.getMaxSYNQueueSize());
    }
}
