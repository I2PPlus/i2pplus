package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Server-side I/O transfer pool sizing and async body-handoff behaviour for
 * {@link I2PTunnelHTTPServer}.
 *
 * <p>Regression guards for the body-stall bug: CompressedRequestor used to block
 * a runner/executor thread on {@code runOnIO} for the entire Server→Client
 * transfer while each tunnel's I/O pool was floored at 2 threads. Concurrent
 * eepsite downloads then queued behind each other, so response headers reached
 * the browser while the body never started within the client's 20s body-stall
 * window — downloads died with 0 body bytes.
 */
public class I2PTunnelHTTPServerBodyStartTest {

    private int _origBudget;
    private ThreadPoolExecutor _pool;

    @Before
    public void setUp() {
        _origBudget = I2PTunnelHTTPServer.ioTransferThreads;
        _pool = null;
    }

    @After
    public void tearDown() {
        I2PTunnelHTTPServer.ioTransferThreads = _origBudget;
        if (_pool != null) {
            _pool.shutdownNow();
        }
    }

    /** Per-tunnel floor must leave room for several concurrent body transfers. */
    @Test
    public void testIOPoolFloorAllowsConcurrentBodies() {
        assertTrue("I/O pool floor too low for concurrent downloads: " +
                   I2PTunnelHTTPServer.IO_POOL_FLOOR,
                   I2PTunnelHTTPServer.IO_POOL_FLOOR >= 8);
        _pool = I2PTunnelHTTPServer.createIOExecutor(1);
        assertTrue("pool must respect raised floor",
                   _pool.getCorePoolSize() >= I2PTunnelHTTPServer.IO_POOL_FLOOR);
        assertTrue("core == max for fixed pool",
                   _pool.getCorePoolSize() == _pool.getMaximumPoolSize());
    }

    /** Global budget clamp must not collapse to a 2-thread regime. */
    @Test
    public void testSetIOTransferThreadsClamp() {
        I2PTunnelHTTPServer.setIOTransferThreads(1);
        assertTrue("minimum budget too low: " + I2PTunnelHTTPServer.getIOTransferThreads(),
                   I2PTunnelHTTPServer.getIOTransferThreads() >= I2PTunnelHTTPServer.IO_POOL_FLOOR);
        I2PTunnelHTTPServer.setIOTransferThreads(10_000);
        assertTrue("maximum budget wrong: " + I2PTunnelHTTPServer.getIOTransferThreads(),
                   I2PTunnelHTTPServer.getIOTransferThreads() <= I2PTunnelHTTPServer.IO_THREADS_MAX);
    }

    /**
     * Allocation with many tunnels must still honour the floor so a busy
     * router cannot starve an eepsite down to 2 body threads.
     */
    @Test
    public void testAllocateRespectsIOPoolFloor() {
        int floor = I2PTunnelHTTPServer.IO_POOL_FLOOR;
        int n = 10;
        int budget = n * floor;
        int[] desired = new int[n];
        Arrays.fill(desired, budget);
        int[] alloc = TunnelControllerGroup.allocateServerThreads(budget, desired, floor);
        assertEquals(n, alloc.length);
        for (int x : alloc) {
            assertTrue("allocation below floor: " + x, x >= floor);
        }
    }

    /**
     * Body phase must start the Sender on a worker and release the submitting
     * thread without waiting for the transfer to finish.
     */
    @Test
    public void testHandOffReleasesCallerBeforeTransferCompletes() throws Exception {
        final CountDownLatch blockBody = new CountDownLatch(1);
        final CountDownLatch bodyStarted = new CountDownLatch(1);
        final CountDownLatch callerReleased = new CountDownLatch(1);
        final AtomicInteger bytes = new AtomicInteger();

        InputStream in = new InputStream() {
            private boolean _done;
            @Override
            public int read() throws IOException {
                try {
                    if (!_done) {
                        _done = true;
                        bodyStarted.countDown();
                        if (!blockBody.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("body never released");
                        }
                        return 0x42;
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException(ie);
                }
                return -1;
            }
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int v = read();
                if (v < 0) {return -1;}
                b[off] = (byte) v;
                bytes.incrementAndGet();
                return 1;
            }
        };
        OutputStream out = new ByteArrayOutputStream();

        _pool = I2PTunnelHTTPServer.createIOExecutor(I2PTunnelHTTPServer.IO_POOL_FLOOR);
        final I2PTunnelHTTPServer.Sender sender =
                new I2PTunnelHTTPServer.Sender(out, in, "test Server -> Client", null);

        Thread caller = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    I2PTunnelHTTPServer.handOffBody(_pool, sender, "test");
                } catch (IOException ioe) {
                    // caller falls through; failure recorded on latch path
                }
                callerReleased.countDown();
            }
        }, "body-caller");
        caller.start();

        assertTrue("caller should release before body finishes",
                   callerReleased.await(2, TimeUnit.SECONDS));
        assertTrue("sender should have started", bodyStarted.await(2, TimeUnit.SECONDS));

        blockBody.countDown();
        caller.join(5000);
        // Wait for pool worker to finish the copy.
        long deadline = System.currentTimeMillis() + 5000;
        while (bytes.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("body should deliver bytes after release", 1, bytes.get());
    }

    /** Rejected execution falls back to inline run so the body is never dropped. */
    @Test
    public void testHandOffFallsBackWhenPoolRejects() throws Exception {
        ThreadPoolExecutor full = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(1));
        full.execute(new Runnable() {
            @Override
            public void run() {
                try {Thread.sleep(2000);}
                catch (InterruptedException ie) {Thread.currentThread().interrupt();}
            }
        });
        full.execute(new Runnable() {
            @Override
            public void run() { /* occupies the single queue slot */ }
        });

        final InputStream in = new InputStream() {
            private int _n;
            @Override
            public int read() {
                return _n++ < 1 ? 0x41 : -1;
            }
        };
        OutputStream out = new ByteArrayOutputStream();
        final I2PTunnelHTTPServer.Sender sender =
                new I2PTunnelHTTPServer.Sender(out, in, "fallback", null);
        try {
            I2PTunnelHTTPServer.handOffBody(full, sender, "fallback");
        } finally {
            full.shutdownNow();
        }
        assertTrue("inline fallback must run the sender",
                   ((ByteArrayOutputStream) out).size() >= 1);
    }

    /** Sender flushes every chunk so partial bodies reach the browser promptly. */
    @Test
    public void testSenderFlushesEachChunk() throws Exception {
        final AtomicInteger flushes = new AtomicInteger();
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) { /* discard */ }
            @Override
            public void write(byte[] b, int off, int len) { /* discard */ }
            @Override
            public void flush() { flushes.incrementAndGet(); }
        };
        InputStream in = new InputStream() {
            private int _n;
            @Override
            public int read() {
                if (_n < 3) {_n++; return 0x20;}
                return -1;
            }
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int v = read();
                if (v < 0) {return -1;}
                b[off] = (byte) v;
                return 1;
            }
        };
        I2PTunnelHTTPServer.Sender s =
                new I2PTunnelHTTPServer.Sender(out, in, "flush-test", null);
        s.run();
        assertTrue("expected flushes, got " + flushes.get(), flushes.get() >= 3);
        assertEquals(3, s.getBytesTransferred());
        assertNull(s.getFailure());
    }

    /** Stall timeout getter/setter stays in a sane range for live tuning. */
    @Test
    public void testStallTimeoutClamp() {
        long orig = I2PTunnelHTTPServer.getIOStallTimeoutMs();
        try {
            I2PTunnelHTTPServer.setIOStallTimeoutMs(1);
            assertTrue(I2PTunnelHTTPServer.getIOStallTimeoutMs() >= 5000);
            I2PTunnelHTTPServer.setIOStallTimeoutMs(1_000_000);
            assertTrue(I2PTunnelHTTPServer.getIOStallTimeoutMs() <= 300_000);
        } finally {
            I2PTunnelHTTPServer.setIOStallTimeoutMs(orig);
        }
    }

    /** Global stall counter is monotonically increasing (Tuner signal). */
    @Test
    public void testStallCounterMonotonic() {
        long a = I2PTunnelHTTPServer.getStallEventCount();
        assertTrue(a >= 0);
        assertTrue(I2PTunnelHTTPServer.getStallEventCount() >= a);
    }

    /** Unused keeps AtomicLong import honest if asserts grow. */
    @Test
    public void testActiveCountNonNegative() {
        assertTrue(I2PTunnelHTTPServer.getIOTransferActiveCount() >= 0);
        AtomicLong ignored = new AtomicLong();
        assertEquals(0, ignored.get());
    }
}
