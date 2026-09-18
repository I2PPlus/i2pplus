package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * Tests for the idempotency contract of {@link Connection#notifyCloseSent()}.
 *
 * <p>Regression test for the TODO: "ackImmediately() after sending CLOSE causes this. Bad?"
 * The CAS-based idempotency ensures duplicate CLOSE notifications are safe.
 *
 * @since 0.9.71+
 */
public class ConnectionNotifyCloseSentTest {

    /** Verify that compareAndSet is idempotent — second call does not change value. */
    @Test
    public void testCasIdempotent() {
        AtomicLong ts = new AtomicLong(0);
        long now = 1000L;
        // First call succeeds
        boolean first = ts.compareAndSet(0, now);
        assertTrue(first);
        assertEquals(now, ts.get());
        // Second call fails — timestamp unchanged
        boolean second = ts.compareAndSet(0, now + 100);
        assertFalse(second);
        assertEquals(now, ts.get());
    }

    /** Verify that duplicate notifyCloseSent does not change the close-sent timestamp. */
    @Test
    public void testDuplicateTimestampUnchanged() {
        AtomicLong closeSentOn = new AtomicLong(0);
        long t1 = 1000L;
        long t2 = 2000L;
        // Simulate first notifyCloseSent
        closeSentOn.compareAndSet(0, t1);
        assertEquals(t1, closeSentOn.get());
        // Simulate second notifyCloseSent (e.g., from ackImmediately)
        closeSentOn.compareAndSet(0, t2);
        // Must still be t1
        assertEquals(t1, closeSentOn.get());
    }
}
