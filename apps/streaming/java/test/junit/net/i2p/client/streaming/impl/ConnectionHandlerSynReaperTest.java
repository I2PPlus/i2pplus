package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for the SYN accept-queue reaper mechanics that replaced the two
 * per-SYN {@code TimedEvent}s (one at enqueue, one at dequeue).
 *
 * <p>The old model posted O(n) timer events per second under a SYN flood on
 * the shared handler timer; the {@link ConnectionHandler.SynReaper} is one
 * self-rescheduling event that scans the enqueue table every
 * {@link ConnectionHandler#SYN_SWEEP_INTERVAL}. These tests pin the expiry
 * boundary (the timeout is snapshotted at enqueue — the reaper and the client
 * must agree), and the sweep-vs-timeout sizing argument that makes the roughly
 * one-interval overshoot negligible.
 *
 * @since 0.9.71
 */
public class ConnectionHandlerSynReaperTest {

    @Test
    public void testNotExpiredBeforeWindowElapses() {
        long enqueued = 1000;
        int timeout = 10000;
        ConnectionHandler.SynEntry e = new ConnectionHandler.SynEntry(enqueued, timeout);
        assertFalse(e.expired(1000));     // just enqueued
        assertFalse(e.expired(10999));    // one ms short
        assertTrue(e.expired(11000));     // exact end of window
    }

    @Test
    public void testExpiredOnceWindowElapses() {
        long enqueued = 7000;
        int timeout = 5000;
        ConnectionHandler.SynEntry e = new ConnectionHandler.SynEntry(enqueued, timeout);
        assertTrue(e.expired(12000));     // equal to enqueued+timeout
        assertTrue(e.expired(12001));
    }

    @Test
    public void testClockSkewBackwardsIsNotExpired() {
        long enqueued = 5000;
        int timeout = 1000;
        ConnectionHandler.SynEntry e = new ConnectionHandler.SynEntry(enqueued, timeout);
        assertFalse("negative elapsed must not expire", e.expired(1000));
    }

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

    @Test
    public void testSweepIntervalBelowAnyStressFloor() {
        // The sweep adds at most one interval of overshoot to an expiry; it
        // must be a small fraction of the lowest accept timeout the adaptive
        // clamp can produce, so reaping lag never matters.
        assertTrue(ConnectionHandler.SYN_SWEEP_INTERVAL < ConnectionHandler.SYN_STRESS_MIN_TIMEOUT / 2);
    }
}
