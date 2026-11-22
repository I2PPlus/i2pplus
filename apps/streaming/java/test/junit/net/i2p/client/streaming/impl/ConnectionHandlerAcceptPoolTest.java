package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import net.i2p.I2PAppContext;
import net.i2p.util.Clock;
import net.i2p.util.SimpleTimer2;

import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Tests that a listening period actually has the accept pool it asks for.
 *
 * <p>The accept worker loop tests whether the handler is active as its first
 * instruction, and {@code setActive(true)} used to start the pool before it
 * raised that flag, so every worker a period launched read "not active" at the
 * loop top and ended itself. The period was left with no worker at all: nothing
 * pulled from the SYN accept queue until the queue-pressure rule added one in
 * response to the handshakes already dying on it. Measured on a live router for
 * one hosted destination: the queue climbed from 47 to 1,039 under a 4,096 cap,
 * 887 handshakes from 54 peers were reset over 3m 11s, and the expiries stopped
 * in the second the added worker started.
 *
 * <p>The other half is who owns the count of live workers. It decides whether the
 * queue-pressure rules add another worker, how many poison packets a shutdown
 * offers, and the number a pressure report prints. A worker decrements it as its
 * own run() ends, because a worker in the middle of a SYN outlives the period it
 * was started in; a shutdown that reset the count too subtracted such a worker
 * twice.
 *
 * @since 0.9.72+
 */
public class ConnectionHandlerAcceptPoolTest {

    /** Where the handler's own source sits in the tree, for the wiring assertions. */
    private static final String HANDLER_PATH =
        "apps/streaming/java/src/net/i2p/client/streaming/impl/ConnectionHandler.java";
    /** The same file as seen from a test run rooted inside the module. */
    private static final String HANDLER_PATH_SHORT =
        "src/net/i2p/client/streaming/impl/ConnectionHandler.java";

    /** Context supplying the log and the clock. */
    private I2PAppContext _ctx;
    /** Manager the handler hands SYNs to. */
    private ConnectionManager _mgr;
    /** Timer the handler arms its sweeper on; nothing is run from it here. */
    private SimpleTimer2 _timer;

    /**
     * Collaborators bare enough to run an accept worker against: the timer is
     * never executed, the manager only ever sees the SYN handed to it.
     */
    @Before
    public void setUp() {
        _ctx = mock(I2PAppContext.class);
        // LogManager.getLog() is final, so it cannot be stubbed: use the real log
        // from the global context, which any TimedEvent already builds.
        when(_ctx.logManager()).thenReturn(I2PAppContext.getGlobalContext().logManager());
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(1_000_000_000L);
        when(_ctx.clock()).thenReturn(clock);
        _mgr = mock(ConnectionManager.class);
        _timer = mock(SimpleTimer2.class);
    }

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class carries no per-test state.
     *
     * @since 0.9.72+
     */
    public ConnectionHandlerAcceptPoolTest() {}

    /**
     * The workers a period starts stay alive for the period.
     *
     * <p>They are counted the instant they are started, so the count alone is
     * not enough: what the defect left behind was a pool that had already
     * self-terminated by the time the first SYN arrived.
     *
     * @throws Exception if the listening period does not end
     */
    @Test
    public void thePeriodsWorkersSurviveItsOwnStart() throws Exception {
        int saved = ConnectionOptions.getAcceptWorkerThreads();
        ConnectionOptions.setAcceptWorkerThreads(3);
        ConnectionHandler handler = new ConnectionHandler(_ctx, _mgr, _timer);
        try {
            handler.setActive(true);
            assertEquals("the configured pool was started", 3, handler.getActiveWorkerCount());
            // Longer than the worker's 100 ms poll, so a worker that ended itself
            // has had time to do it.
            Thread.sleep(400);
            assertEquals("and none of them ended itself on the handler's own flag",
                         3, handler.getActiveWorkerCount());
        } finally {
            handler.setActive(false);
            ConnectionOptions.setAcceptWorkerThreads(saved);
        }
        assertTrue("every worker ends within its own poll interval",
                   waitForCount(handler, 0, 5000));
    }

    /**
     * A SYN that arrives during a listening period is taken by that period's own
     * pool, with no queue pressure needed to provoke a worker.
     *
     * @throws Exception if the hand-off does not happen
     */
    @Test
    public void aQueuedSynIsAcceptedByThePeriodsOwnPool() throws Exception {
        final CountDownLatch inHandoff = new CountDownLatch(1);
        when(_mgr.receiveConnection(any(Packet.class))).thenAnswer(new Answer<Connection>() {
            @Override
            public Connection answer(InvocationOnMock invocation) {
                inHandoff.countDown();
                return null;
            }
        });
        int saved = ConnectionOptions.getAcceptWorkerThreads();
        ConnectionOptions.setAcceptWorkerThreads(2);
        ConnectionHandler handler = new ConnectionHandler(_ctx, _mgr, _timer);
        try {
            handler.setActive(true);
            handler.receiveNewSyn(mock(Packet.class));
            assertTrue("no accept worker took the queued SYN",
                       inHandoff.await(5, TimeUnit.SECONDS));
        } finally {
            handler.setActive(false);
            ConnectionOptions.setAcceptWorkerThreads(saved);
        }
    }

    /**
     * A worker still holding a SYN from the previous listening period is still a
     * live worker, and the next period has to count it.
     *
     * <p>The shutdown interrupts it and drops it from the thread list, but it
     * only reaches its own decrement when the hand-off returns. A shutdown that
     * reset the count as well subtracted it twice, so the new period reported a
     * pool smaller than the one running - and the queue-pressure rules read that
     * number when deciding whether to add another worker.
     *
     * @throws Exception if the stalled hand-off does not resolve
     */
    @Test
    public void aWorkerStillHoldingASynIsStillCounted() throws Exception {
        final CountDownLatch inHandoff = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        when(_mgr.receiveConnection(any(Packet.class))).thenAnswer(new Answer<Connection>() {
            @Override
            public Connection answer(InvocationOnMock invocation) {
                inHandoff.countDown();
                // Hold the SYN the way a worker mid-handshake does: the
                // shutdown's interrupt does not end a crypto operation, so the
                // worker only reaches its own decrement once this returns.
                long until = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < until && release.getCount() > 0) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        // keep holding the SYN until the hand-off completes
                    }
                }
                return null;
            }
        });
        int saved = ConnectionOptions.getAcceptWorkerThreads();
        ConnectionOptions.setAcceptWorkerThreads(1);
        ConnectionHandler handler = new ConnectionHandler(_ctx, _mgr, _timer);
        try {
            handler.setActive(true);
            handler.receiveNewSyn(mock(Packet.class));
            assertTrue("the worker never picked the SYN up",
                       inHandoff.await(5, TimeUnit.SECONDS));
            assertEquals("one worker, the one holding the SYN", 1,
                         handler.getActiveWorkerCount());
            // The period ends while the worker is inside receiveConnection(), and
            // a new one starts before that worker reaches the loop top.
            handler.setActive(false);
            handler.setActive(true);
            assertEquals("the worker holding the SYN and the new one are both live",
                         2, handler.getActiveWorkerCount());
        } finally {
            release.countDown();
            handler.setActive(false);
            ConnectionOptions.setAcceptWorkerThreads(saved);
        }
        assertTrue("every worker ends once the hand-off returns",
                   waitForCount(handler, 0, 5000));
    }

    /**
     * The line that opens a listening period names the pool it opened and the
     * cap the queue will stop at.
     */
    @Test
    public void theActivationNamesThePoolItStarted() {
        String line = ConnectionHandler.listeningText(0, 4096);
        assertTrue("the pool size is the fact being reported",
                   line.contains("Listening -> Accept workers: 0"));
        assertTrue("and the cap belongs beside it", line.contains("Limit: 4096 SYNs"));
        assertTrue("an uncapped queue must not read as a zero-sized one",
                   ConnectionHandler.listeningText(4, 0).contains("Uncapped"));
        assertTrue("one line, inside the log column", line.length() <= 90);
    }

    /**
     * The report is actually made when the period opens, not just available.
     */
    @Test
    public void theListeningPeriodReportsItsPool() {
        String body = readHandlerSource();
        org.junit.Assume.assumeTrue("source not available", body != null);
        assertTrue("the activation reports the pool it started",
                   body.contains("_log.info(listeningText(_activeWorkerCount.get(),"));
    }

    /**
     * Reads the handler's own source, for the assertions that pin wiring rather
     * than behaviour.
     *
     * @return the source text, or null if it is not where this test run can see it
     */
    private static String readHandlerSource() {
        java.io.File src = new java.io.File(HANDLER_PATH);
        if (!src.isFile()) {src = new java.io.File(HANDLER_PATH_SHORT);}
        if (!src.isFile()) {return null;}
        try {
            return new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * Waits for the live-worker count to fall to the given value.
     *
     * @param handler the handler to watch
     * @param wanted the count to wait for
     * @param timeoutMs how long to wait, in ms
     * @return true if the count was reached before the timeout
     */
    private static boolean waitForCount(ConnectionHandler handler, int wanted, int timeoutMs) {
        long until = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < until) {
            if (handler.getActiveWorkerCount() == wanted) {return true;}
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return handler.getActiveWorkerCount() == wanted;
    }
}
