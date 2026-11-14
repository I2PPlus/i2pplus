package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for the inbound flood guard. It used to count messages only, which implies a volume at
 * exactly one block size: a peer splits its replies into whatever pieces it likes - every client
 * caps its own piece size, and our own chunk size is configurable down to 1 KiB - so a peer
 * streaming 200 KB/s in 1 KiB blocks was cut off as a flooder. The byte bound is the volume the
 * old count implied at the default chunk size; the count bound is what catches a peer that sends
 * only messages with no payload.
 *
 * @since 0.9.72+
 */
public class FloodGuardTest {

    /** Bytes one message of the given payload takes on the wire: length prefix and id byte. */
    private static final int OVERHEAD = 4 + 1;

    /** The default chunk size, the only size the old count was meaningful at. */
    private static final int DEFAULT_CHUNK = 16 * 1024;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public FloodGuardTest() {}

    /**
     * Bytes one window would hold if a peer sent that many blocks of that size.
     *
     * @param messages how many blocks the peer sent
     * @param block the payload of each block, in bytes
     * @return the bytes counted for the window
     */
    private static long windowBytes(int messages, int block) {
        return messages * ((long) block + OVERHEAD);
    }

    /**
     * A peer answering in small blocks is not cut off: two thousand 1 KiB blocks is 2 MB, a
     * fraction of what the window allows.
     */
    @Test
    public void testSmallBlocksAreNotAFlood() {
        long bytes = windowBytes(2000, 1024);
        assertFalse(PeerConnectionIn.floodByBytes(bytes));
        assertFalse(PeerConnectionIn.floodByCount(2000));
    }

    /**
     * Eight thousand small blocks in a window - 800 a second - is still not a flood, which is
     * what the old count of two thousand refused.
     */
    @Test
    public void testManySmallBlocksAreNotAFlood() {
        long bytes = windowBytes(8000, 1024);
        assertFalse(PeerConnectionIn.floodByBytes(bytes));
        assertFalse(PeerConnectionIn.floodByCount(8000));
    }

    /**
     * The volume the old count implied at the default chunk size is still allowed, exactly.
     */
    @Test
    public void testTheImpliedVolumeIsAllowed() {
        assertFalse(PeerConnectionIn.floodByBytes(PeerConnectionIn.MAX_BYTES_PER_WINDOW));
        assertFalse(PeerConnectionIn.floodByBytes(windowBytes(2000, DEFAULT_CHUNK)));
    }

    /**
     * A peer that pushes more than that volume is still dropped, whatever size it splits it
     * into.
     */
    @Test
    public void testAVolumeFloodIsCaught() {
        assertTrue(PeerConnectionIn.floodByBytes(windowBytes(4000, 128 * 1024)));
        assertTrue(PeerConnectionIn.floodByBytes(windowBytes(40000, 1024)));
    }

    /**
     * A peer that sends only empty messages never moves the byte counter, so the count bound is
     * what stops it - and it stops it well above any honest chatter rate.
     */
    @Test
    public void testAChatterFloodIsCaughtByTheCount() {
        assertFalse(PeerConnectionIn.floodByBytes(windowBytes(20000, 0)));
        assertFalse(PeerConnectionIn.floodByCount(20000));
        assertTrue(PeerConnectionIn.floodByCount(20001));
    }
}
