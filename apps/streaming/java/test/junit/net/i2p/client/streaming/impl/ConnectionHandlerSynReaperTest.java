package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import net.i2p.I2PAppContext;
import net.i2p.util.SimpleTimer2;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for the SYN accept-queue reaper mechanics that replaced the two
 * per-SYN {@code TimedEvent}s (one at enqueue, one at dequeue).
 *
 * <p>The old model posted O(n) timer events per second under a SYN flood on
 * the shared handler timer; the {@code SynReaper} is one
 * self-rescheduling event that scans the enqueue table every
 * {@link ConnectionHandler#SYN_SWEEP_INTERVAL}. These tests pin the expiry
 * boundary (the timeout is snapshotted at enqueue — the reaper and the client
 * must agree), and the sweep-vs-timeout sizing argument that makes the roughly
 * one-interval overshoot negligible.
 *
 * @since 0.9.71
 */
public class ConnectionHandlerSynReaperTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class carries no per-test state.
     *
     * @since 0.9.72+
     */
    public ConnectionHandlerSynReaperTest() {}

    /** Context supplying the log and the accept-worker count. */
    private I2PAppContext _ctx;
    /** Manager the handler reports to; never touched by these tests. */
    private ConnectionManager _mgr;
    /** Timer the handler arms its sweeper on, captured rather than run. */
    private SimpleTimer2 _timer;

    /**
     * Collaborators bare enough to build a handler against: nothing here runs a
     * worker or a timer pass, it only records what the handler arms.
     */
    @Before
    public void setUp() {
        _ctx = mock(I2PAppContext.class);
        // LogManager.getLog() is final, so it cannot be stubbed: hand the handler
        // a real log from the global context, which any TimedEvent already builds.
        when(_ctx.logManager()).thenReturn(I2PAppContext.getGlobalContext().logManager());
        _mgr = mock(ConnectionManager.class);
        _timer = mock(SimpleTimer2.class);
    }

    /** The whole window must pass before a queued SYN counts as expired. */
    @Test
    public void testNotExpiredBeforeWindowElapses() {
        long enqueued = 1000;
        int timeout = 10000;
        ConnectionHandler.SynEntry e = new ConnectionHandler.SynEntry(enqueued, timeout);
        assertFalse(e.expired(1000));     // just enqueued
        assertFalse(e.expired(10999));    // one ms short
        assertTrue(e.expired(11000));     // exact end of window
    }

    /** The window is inclusive: the end of it is already expired. */
    @Test
    public void testExpiredOnceWindowElapses() {
        long enqueued = 7000;
        int timeout = 5000;
        ConnectionHandler.SynEntry e = new ConnectionHandler.SynEntry(enqueued, timeout);
        assertTrue(e.expired(12000));     // equal to enqueued+timeout
        assertTrue(e.expired(12001));
    }

    /** A clock step backwards must not expire a SYN that has not waited. */
    @Test
    public void testClockSkewBackwardsIsNotExpired() {
        long enqueued = 5000;
        int timeout = 1000;
        ConnectionHandler.SynEntry e = new ConnectionHandler.SynEntry(enqueued, timeout);
        assertFalse("negative elapsed must not expire", e.expired(1000));
    }

    /**
     * The window quoted to the client at enqueue is the one the reaper re-quotes,
     * so both snapshot fields survive the periodic scan untouched.
     */
    @Test
    public void testSnapshotFieldsRetained() {
        long enqueued = 42;
        int timeout = 6000;
        ConnectionHandler.SynEntry e = new ConnectionHandler.SynEntry(enqueued, timeout);
        // the reaper re-quotes via the snapshot, so both fields must survive
        // the periodic scan untouched
        assertEquals(timeout, e.timeoutMs);
        assertEquals(enqueued, e.enqueuedMs);
    }

    /** Reaping lag must stay a rounding error against the shortest timeout. */
    @Test
    public void testSweepIntervalBelowAnyStressFloor() {
        // The sweep adds at most one interval of overshoot to an expiry; it
        // must be a small fraction of the lowest accept timeout the adaptive
        // clamp can produce, so reaping lag never matters.
        assertTrue(ConnectionHandler.SYN_SWEEP_INTERVAL < ConnectionHandler.SYN_STRESS_MIN_TIMEOUT / 2);
    }

    /**
     * One sweeper for the handler's life, not one per activation.
     *
     * <p>The sweeper re-arms itself from its own pass while the handler is
     * active, so an activation that lands before the previous period's sweeper
     * next runs leaves that sweeper alive alongside the new one. Measured on a
     * live router: three sweeper instances swept one accept queue in the same
     * window - 473, 396 and 18 expiry lines from three different timer threads -
     * each making its own scale-up decision off a queue the others had already
     * looked at.
     */
    @Test
    public void oneSweeperSurvivesRepeatedActivations() {
        ConnectionHandler handler = new ConnectionHandler(_ctx, _mgr, _timer);
        int saved = ConnectionOptions.getAcceptWorkerThreads();
        ConnectionOptions.setAcceptWorkerThreads(1);
        try {
            handler.setActive(true);
            handler.setActive(false);
            handler.setActive(true);
            ArgumentCaptor<SimpleTimer2.TimedEvent> captor =
                ArgumentCaptor.forClass(SimpleTimer2.TimedEvent.class);
            verify(_timer, times(2)).addEvent(captor.capture(),
                                              eq(ConnectionHandler.SYN_SWEEP_INTERVAL));
            assertSame("the sweeper is re-armed, not rebuilt, by a new activation",
                       captor.getAllValues().get(0), captor.getAllValues().get(1));
        } finally {
            ConnectionOptions.setAcceptWorkerThreads(saved);
            handler.setActive(false);
        }
    }

    /**
     * The period's sweeper is cancelled on the way out, so it cannot sweep a
     * queue the next period has already cleared, and cannot add a worker to a
     * handler that is shutting its pool down.
     *
     * @throws Exception if the handler source cannot be read back from disk
     */
    @Test
    public void theSweeperIsBuiltOnceAndCancelledOnDeactivation() throws Exception {
        java.io.File src = new java.io.File(
            "apps/streaming/java/src/net/i2p/client/streaming/impl/ConnectionHandler.java");
        if (!src.isFile())
            {src = new java.io.File("src/net/i2p/client/streaming/impl/ConnectionHandler.java");}
        org.junit.Assume.assumeTrue("source not available", src.isFile());
        String body = new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
        assertTrue("deactivation must cancel the sweeper",
                   body.contains("_synReaper.cancel();"));
        assertTrue("activation re-arms the handler's own sweeper",
                   body.contains("_timer.addEvent(_synReaper, SYN_SWEEP_INTERVAL);"));
        String marker = "new SynReaper()";
        int built = 0;
        for (int i = body.indexOf(marker); i >= 0; i = body.indexOf(marker, i + 1))
            built++;
        assertEquals("exactly one sweeper is ever constructed, as the field initializer",
                     1, built);
    }
}
