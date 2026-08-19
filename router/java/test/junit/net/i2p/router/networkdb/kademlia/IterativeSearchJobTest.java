package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.*;

import org.junit.Test;

public class IterativeSearchJobTest {

    @Test
    public void testSkipsWhenAlternativesRemain() {
        assertTrue(IterativeSearchJob.shouldSkipPeer(true, false, false, 2));
        assertTrue(IterativeSearchJob.shouldSkipPeer(false, true, false, 5));
        assertTrue(IterativeSearchJob.shouldSkipPeer(false, false, true, 3));
        assertTrue(IterativeSearchJob.shouldSkipPeer(true, true, true, 10));
    }

    @Test
    public void testNeverSkipsLastCandidate() {
        assertFalse(IterativeSearchJob.shouldSkipPeer(true, false, false, 1));
        assertFalse(IterativeSearchJob.shouldSkipPeer(false, true, false, 1));
        assertFalse(IterativeSearchJob.shouldSkipPeer(false, false, true, 1));
        assertFalse(IterativeSearchJob.shouldSkipPeer(true, true, true, 1));
    }

    @Test
    public void testNeverSkipsOnNoSoftSignals() {
        assertFalse(IterativeSearchJob.shouldSkipPeer(false, false, false, 100));
        assertFalse(IterativeSearchJob.shouldSkipPeer(false, false, false, 1));
    }

    @Test
    public void testZeroRemainingNeverSkips() {
        assertFalse(IterativeSearchJob.shouldSkipPeer(true, false, false, 0));
        assertFalse(IterativeSearchJob.shouldSkipPeer(true, true, true, 0));
    }
}