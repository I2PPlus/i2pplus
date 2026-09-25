package net.i2p.i2ptunnel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

/**
 * Tests for the I/O transfer pool and Sender progress tracking in
 * I2PTunnelHTTPServer.
 *
 * Headless — no RouterContext required.
 */
public class I2PTunnelHTTPServerIOTest extends TestCase {

    /**
     * Test that an I/O pool factory creates a valid, non-shutdown executor.
     */
    public void testIOPoolCreation() {
        ThreadPoolExecutor pool = I2PTunnelHTTPServer.createIOExecutor(4);
        assertNotNull("I/O pool must not be null", pool);
        assertFalse("I/O pool must not be shutdown", pool.isShutdown());
        assertTrue("I/O pool core size respects floor", pool.getCorePoolSize() >= I2PTunnelHTTPServer.IO_POOL_FLOOR);
        pool.shutdownNow();
    }

    /**
     * Each tunnel gets its own I/O pool instance (isolation), not a shared singleton.
     */
    public void testIOPoolsAreIsolated() {
        ThreadPoolExecutor a = I2PTunnelHTTPServer.createIOExecutor(4);
        ThreadPoolExecutor b = I2PTunnelHTTPServer.createIOExecutor(4);
        try {
            assertNotSame("I/O pools must be per-tunnel, not a singleton", a, b);
        } finally {
            a.shutdownNow();
            b.shutdownNow();
        }
    }

    /**
     * Test that Sender tracks bytes transferred correctly.
     */
    public void testSenderBytesTransferred() throws Exception {
        byte[] data = "Hello, World! This is test data for the Sender.".getBytes();
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Sender(OutputStream out, InputStream in, String name, Log log)
        I2PTunnelHTTPServer.Sender sender = new I2PTunnelHTTPServer.Sender(out, in, "test", null);
        sender.run();

        assertEquals("Bytes transferred must match input length", data.length, sender.getBytesTransferred());
        assertTrue("Output must match input", java.util.Arrays.equals(data, out.toByteArray()));
        assertNull("No failure expected", sender.getFailure());
    }

    /**
     * Test that Sender sets _lastReadNanos after a successful read.
     */
    public void testSenderLastReadNanos() throws Exception {
        byte[] data = "Test data".getBytes();
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        I2PTunnelHTTPServer.Sender sender = new I2PTunnelHTTPServer.Sender(out, in, "test", null);
        long beforeNanos = System.nanoTime();
        sender.run();
        long afterNanos = System.nanoTime();

        assertTrue("LastReadNanos must be set", sender.getLastReadNanos() > 0);
        assertTrue("LastReadNanos must be after start",
                   sender.getLastReadNanos() >= beforeNanos);
        assertTrue("LastReadNanos must be before or at end",
                   sender.getLastReadNanos() <= afterNanos);
    }

    /**
     * Test that Sender reports failure on IOException.
     */
    public void testSenderReportsFailure() {
        InputStream failIn = new InputStream() {
            public int read() throws IOException {
                throw new IOException("Test error");
            }
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        I2PTunnelHTTPServer.Sender sender = new I2PTunnelHTTPServer.Sender(out, failIn, "test-fail", null);
        sender.run();

        assertNotNull("Failure must be reported", sender.getFailure());
        assertEquals("Test error", sender.getFailure().getMessage());
        assertEquals("No bytes transferred on error", 0, sender.getBytesTransferred());
    }

    /**
     * Test that Sender handles empty stream.
     */
    public void testSenderEmptyStream() throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        I2PTunnelHTTPServer.Sender sender = new I2PTunnelHTTPServer.Sender(out, in, "test-empty", null);
        sender.run();

        assertEquals("No bytes transferred from empty stream", 0, sender.getBytesTransferred());
        assertNull("No failure expected", sender.getFailure());
    }

    /**
     * Test that Sender handles large data correctly.
     */
    public void testSenderLargeData() throws Exception {
        byte[] data = new byte[256 * 1024]; // 256KB
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        I2PTunnelHTTPServer.Sender sender = new I2PTunnelHTTPServer.Sender(out, in, "test-large", null);
        sender.run();

        assertEquals("All bytes must be transferred", data.length, sender.getBytesTransferred());
        assertTrue("Output must match input", java.util.Arrays.equals(data, out.toByteArray()));
    }

    /**
     * Test Sender stall detection: a Sender that never provides data should
     * be interruptible via Thread.interrupt() and eventually stop.
     */
    public void testSenderInterruptible() throws Exception {
        // Use a stream that blocks forever on read()
        InputStream blockingIn = new InputStream() {
            public int read() throws IOException {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted");
                }
                return -1;
            }
            public int read(byte[] b, int off, int len) throws IOException {
                return read();
            }
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        I2PTunnelHTTPServer.Sender sender = new I2PTunnelHTTPServer.Sender(out, blockingIn, "test-block", null);

        Thread senderThread = new Thread(sender);
        senderThread.start();

        // Wait a bit for the Sender to start, then interrupt it
        Thread.sleep(100);
        senderThread.interrupt();
        senderThread.join(5000);

        assertFalse("Sender thread must have terminated", senderThread.isAlive());
    }

    /**
     * Test that the I/O pool dynamic resizing works.
     */
    public void testIOPoolResizing() {
        // Save original value
        int origThreads = I2PTunnelHTTPServer.ioTransferThreads;
        try {
            I2PTunnelHTTPServer.setIOTransferThreads(16);
            assertEquals(16, I2PTunnelHTTPServer.getIOTransferThreads());

            I2PTunnelHTTPServer.setIOTransferThreads(64);
            assertEquals(64, I2PTunnelHTTPServer.getIOTransferThreads());

            // Clamp test — floor is IO_POOL_FLOOR (8), not 2
            I2PTunnelHTTPServer.setIOTransferThreads(1);
            assertEquals("Minimum should be the I/O pool floor",
                         I2PTunnelHTTPServer.IO_POOL_FLOOR,
                         I2PTunnelHTTPServer.getIOTransferThreads());

            I2PTunnelHTTPServer.setIOTransferThreads(10_000);
            assertEquals("Maximum should be IO_THREADS_MAX",
                         I2PTunnelHTTPServer.IO_THREADS_MAX,
                         I2PTunnelHTTPServer.getIOTransferThreads());
        } finally {
            I2PTunnelHTTPServer.ioTransferThreads = origThreads;
        }
    }

    /**
     * Test that the stall timeout is configurable and clamped.
     */
    public void testStallTimeoutConfig() {
        long origTimeout = I2PTunnelHTTPServer.ioStallTimeoutMs;
        try {
            I2PTunnelHTTPServer.setIOStallTimeoutMs(30_000L);
            assertEquals(30_000L, I2PTunnelHTTPServer.getIOStallTimeoutMs());

            // Clamp test: minimum 5000ms
            I2PTunnelHTTPServer.setIOStallTimeoutMs(1000L);
            assertEquals("Minimum should be 5000", 5000L, I2PTunnelHTTPServer.getIOStallTimeoutMs());

            // Maximum 300000ms
            I2PTunnelHTTPServer.setIOStallTimeoutMs(600_000L);
            assertEquals("Maximum should be 300000", 300_000L, I2PTunnelHTTPServer.getIOStallTimeoutMs());
        } finally {
            I2PTunnelHTTPServer.ioStallTimeoutMs = origTimeout;
        }
    }

    /**
     * Test that Sender tracks throughput logging threshold (>5s logs).
     * Verify getBytesTransferred is 0 before run.
     */
    public void testSenderInitialStats() throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream("data".getBytes());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        I2PTunnelHTTPServer.Sender sender = new I2PTunnelHTTPServer.Sender(out, in, "init", null);

        assertEquals("Bytes before run", 0, sender.getBytesTransferred());
        assertEquals("LastReadNanos before run", 0, sender.getLastReadNanos());
        assertNull("No failure before run", sender.getFailure());
    }

    /**
     * The I/O pool queue must be bounded so a saturated pool cannot park an
     * unbounded number of sockets with their streams open.
     */
    public void testIOQueueBounded() {
        ThreadPoolExecutor pool = I2PTunnelHTTPServer.createIOExecutor(4);
        try {
            int cap = I2PTunnelHTTPServer.ioQueueCap(
                    Math.max(I2PTunnelHTTPServer.IO_POOL_FLOOR, 4));
            assertTrue("queue capacity floor", cap >= 32);
            assertEquals("executor queue must match ioQueueCap",
                         cap, pool.getQueue().remainingCapacity());
            assertTrue("queue must be bounded",
                       pool.getQueue().remainingCapacity() < Integer.MAX_VALUE);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * ioQueueCap must grow with the worker count while keeping a hard floor.
     */
    public void testIOQueueCapFormula() {
        assertEquals(32, I2PTunnelHTTPServer.ioQueueCap(1));
        assertEquals(32, I2PTunnelHTTPServer.ioQueueCap(4));
        assertEquals(32, I2PTunnelHTTPServer.ioQueueCap(8));
        assertEquals(40, I2PTunnelHTTPServer.ioQueueCap(10));
        assertEquals(64, I2PTunnelHTTPServer.ioQueueCap(16));
    }

    /**
     * When every worker and queue slot is occupied, handOffBody falls back to
     * running the body inline instead of dropping it or throwing.
     */
    public void testHandOffInlineFallbackWhenSaturated() throws Exception {
        final CountDownLatch blocker = new CountDownLatch(1);
        ThreadPoolExecutor pool = I2PTunnelHTTPServer.createIOExecutor(
                I2PTunnelHTTPServer.IO_POOL_FLOOR);
        try {
            int workers = pool.getCorePoolSize();
            int cap = I2PTunnelHTTPServer.ioQueueCap(workers);
            // Occupy every worker and every queue slot with blocked Senders.
            for (int i = 0; i < workers + cap; i++) {
                I2PTunnelHTTPServer.Sender block =
                        new I2PTunnelHTTPServer.Sender(
                                new ByteArrayOutputStream(),
                                new ByteArrayInputStream(new byte[0]), "block", null) {
                            @Override
                            public void run() {
                                try {
                                    blocker.await(10, TimeUnit.SECONDS);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        };
                assertTrue("saturation task " + i + " must be accepted",
                           I2PTunnelHTTPServer.handOffBody(pool, block, "block"));
            }
            assertEquals("queue must be full", cap, pool.getQueue().size());

            // Next submission is rejected by the bounded queue and must run inline.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            I2PTunnelHTTPServer.Sender quick = new I2PTunnelHTTPServer.Sender(
                    out, new ByteArrayInputStream("inline".getBytes()), "inline", null);
            assertFalse("saturated handoff must fall back inline",
                        I2PTunnelHTTPServer.handOffBody(pool, quick, "inline"));
            assertEquals("inline fallback must copy the body",
                         6, quick.getBytesTransferred());
            assertEquals(6, out.size());
        } finally {
            blocker.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * Body watchdog stall decision: pure boundary, disabled, and not-started
     * cases for isBodyWatchdogStalled().
     */
    public void testIsBodyWatchdogStalled() {
        final long now = 1_000_000_000_000L;
        final long stallNs = 60_000L * 1_000_000L;

        assertFalse("task never started must not stall",
                    I2PTunnelHTTPServer.isBodyWatchdogStalled(0, now, stallNs));
        assertFalse("negative stamp must not stall",
                    I2PTunnelHTTPServer.isBodyWatchdogStalled(-1, now, stallNs));
        assertFalse("disabled window must not stall",
                    I2PTunnelHTTPServer.isBodyWatchdogStalled(now - stallNs, now, 0));
        assertFalse("read inside window must not stall",
                    I2PTunnelHTTPServer.isBodyWatchdogStalled(now - stallNs + 1, now, stallNs));
        assertTrue("read exactly at window must stall",
                   I2PTunnelHTTPServer.isBodyWatchdogStalled(now - stallNs, now, stallNs));
        assertTrue("read past window must stall",
                   I2PTunnelHTTPServer.isBodyWatchdogStalled(now - 2 * stallNs, now, stallNs));
    }
}
