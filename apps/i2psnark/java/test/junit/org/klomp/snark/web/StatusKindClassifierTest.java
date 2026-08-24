package org.klomp.snark.web;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for I2PSnarkServlet.classifyStatus(), the pure decision function
 * extracted from buildStatusString().
 *
 * Branch order is load-bearing and mirrors the historical cascade: earlier
 * states win. These tests pin each branch with minimal inputs, plus the
 * precedence relationships that were easy to break during extraction.
 *
 * @since 0.9.71+
 */
public class StatusKindClassifierTest {

    private static I2PSnarkServlet.StatusKind classify(boolean allocating, boolean checking,
            boolean starting, boolean trackerProblems, boolean running, boolean complete,
            int curPeers, int knownPeers, boolean uploading, boolean downloading) {
        return I2PSnarkServlet.classifyStatus(allocating, checking, starting, trackerProblems,
                running, complete, curPeers, knownPeers, uploading, downloading);
    }

    @Test
    public void testCheckingWinsOverEverything() {
        assertEquals(I2PSnarkServlet.StatusKind.CHECKING,
                classify(true, true, true, true, true, false, 5, 5, true, true));
    }

    @Test
    public void testAllocatingSecond() {
        assertEquals(I2PSnarkServlet.StatusKind.ALLOCATING,
                classify(true, false, true, false, true, false, 0, 0, false, false));
    }

    @Test
    public void testTrackerErrorSingleState() {
        // one state regardless of completeness; render layer picks keyword
        assertEquals(I2PSnarkServlet.StatusKind.TRACKER_ERROR,
                classify(false, false, false, true, true, true, 0, 3, false, false));
        assertEquals(I2PSnarkServlet.StatusKind.TRACKER_ERROR,
                classify(false, false, false, true, true, false, 0, 3, false, false));
    }

    @Test
    public void testStarting() {
        assertEquals(I2PSnarkServlet.StatusKind.STARTING,
                classify(false, false, true, false, false, false, 0, 0, false, false));
    }

    @Test
    public void testActivelySeedingRequiresKnownPeersAndUpload() {
        assertEquals(I2PSnarkServlet.StatusKind.SEEDING_ACTIVE,
                classify(false, false, false, false, true, true, 2, 4, true, false));
        // connected but no known peers -> not "actively seeding"
        assertEquals(I2PSnarkServlet.StatusKind.SEEDING_IDLE,
                classify(false, false, false, false, true, true, 2, 0, true, false));
        // known peers but not uploading -> connected idle
        assertEquals(I2PSnarkServlet.StatusKind.SEEDING_CONNECTED_IDLE,
                classify(false, false, false, false, true, true, 2, 4, false, false));
    }

    @Test
    public void testSeedingConnectedIdle() {
        assertEquals(I2PSnarkServlet.StatusKind.SEEDING_CONNECTED_IDLE,
                classify(false, false, false, false, true, true, 1, 1, false, false));
    }

    @Test
    public void testStalledConnectedIdleIncomplete() {
        assertEquals(I2PSnarkServlet.StatusKind.STALLED_CONNECTED_IDLE,
                classify(false, false, false, false, true, false, 1, 1, false, false));
        // downloading traffic breaks the stall
        assertNotEquals(I2PSnarkServlet.StatusKind.STALLED_CONNECTED_IDLE,
                classify(false, false, false, false, true, false, 1, 1, false, true));
    }

    @Test
    public void testSeedingIdleWithoutConnections() {
        assertEquals(I2PSnarkServlet.StatusKind.SEEDING_IDLE,
                classify(false, false, false, false, true, true, 0, 7, false, false));
    }

    @Test
    public void testCompleteStopped() {
        assertEquals(I2PSnarkServlet.StatusKind.COMPLETE_STOPPED,
                classify(false, false, false, false, false, true, 0, 0, false, false));
    }

    @Test
    public void testDownloading() {
        assertEquals(I2PSnarkServlet.StatusKind.DOWNLOADING,
                classify(false, false, false, false, true, false, 3, 9, false, true));
    }

    @Test
    public void testStalledIncompleteConnectedWithUploadOnly() {
        // connected + uploading but no download traffic on an incomplete torrent
        assertEquals(I2PSnarkServlet.StatusKind.STALLED_INCOMPLETE_CONNECTED,
                classify(false, false, false, false, true, false, 2, 8, true, false));
    }

    @Test
    public void testNoPeersVariants() {
        // known peers but nobody connected
        assertEquals(I2PSnarkServlet.StatusKind.NOPEERS_CONNECTED,
                classify(false, false, false, false, true, false, 0, 6, false, false));
        // nothing known at all
        assertEquals(I2PSnarkServlet.StatusKind.NOPEERS_UNKNOWN,
                classify(false, false, false, false, true, false, 0, 0, false, false));
    }

    @Test
    public void testStoppedDefault() {
        // stopped, incomplete
        assertEquals(I2PSnarkServlet.StatusKind.STOPPED_DEFAULT,
                classify(false, false, false, false, false, false, 0, 0, false, false));
        // stopped but peers known: still the default bucket (matches cascade)
        assertEquals(I2PSnarkServlet.StatusKind.STOPPED_DEFAULT,
                classify(false, false, false, false, false, false, 0, 5, false, false));
    }
}
