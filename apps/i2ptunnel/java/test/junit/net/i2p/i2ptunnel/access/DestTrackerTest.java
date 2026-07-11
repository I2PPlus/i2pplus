package net.i2p.i2ptunnel.access;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.data.Hash;

/**
 * Tests DestTracker integration of AccessCounter + Threshold.
 *
 * @since 0.9.70+
 */
public class DestTrackerTest {

    @Test
    public void testRecordAccessReturnsBreach() {
        Hash h = Hash.FAKE_HASH;
        Threshold t = new Threshold(3, 5);
        DestTracker tracker = new DestTracker(h, t);
        assertEquals(h, tracker.getHash());
        assertNotNull(tracker.getCounter());

        long now = 1000;
        assertFalse(tracker.recordAccess(now));
        assertFalse(tracker.recordAccess(now + 1000));
        assertTrue(tracker.recordAccess(now + 2000));
    }

    @Test
    public void testPurge() {
        Hash h = Hash.FAKE_HASH;
        DestTracker tracker = new DestTracker(h, new Threshold(10, 10));
        tracker.recordAccess(1000);
        tracker.recordAccess(2000);
        // purge older than 1500: removes 1000 but keeps 2000
        assertFalse(tracker.purge(1500));
        // purge older than 2500: removes 2000, list empty
        assertTrue(tracker.purge(2500));
    }

    @Test
    public void testNoBreachWithFewAccesses() {
        Hash h = Hash.FAKE_HASH;
        DestTracker tracker = new DestTracker(h, new Threshold(10, 60));
        long now = 1000;
        for (int i = 0; i < 5; i++) {
            assertFalse(tracker.recordAccess(now + i * 1000));
        }
    }
}
