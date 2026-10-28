package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for <code>PeerConnectionIn.maxMessageSize()</code>, the cap on an inbound
 * message body. The cap must be measured from the live pipeline settings:
 * <code>PeerState.PARTSIZE</code> is a runtime value read from
 * <code>i2psnark.pipeline.partsize</code>, and a bound left at the 16 KiB
 * class-load default disconnects every peer that correctly answers a request
 * for a larger part - a request we sent, so a behaving peer pays for it.
 *
 * @since 0.9.72+
 */
public class MaxMessageSizeDecisionTest {

    /** Saved pipeline settings, restored after each test. */
    private int _savedPartsize;

    /** Saved pipeline settings, restored after each test. */
    private int _savedMaxPartsize;

    /**
     * A fixture group for the inbound message-size cap; it holds no state of its
     * own beyond the saved pipeline settings.
     */
    public MaxMessageSizeDecisionTest() {}

    /**
     * Snapshot the static pipeline settings so a test that raises the part size
     * cannot leak into the rest of the suite.
     */
    @Before
    public void setUp() {
        _savedPartsize = PeerState.PARTSIZE;
        _savedMaxPartsize = PeerState.MAX_PARTSIZE;
    }

    /**
     * Restore the static pipeline settings snapshotted by {@link #setUp()}.
     */
    @After
    public void tearDown() {
        PeerState.PARTSIZE = _savedPartsize;
        PeerState.MAX_PARTSIZE = _savedMaxPartsize;
    }

    /**
     * At the shipped defaults the bound is the magnet term, unchanged from the
     * value frozen at class load before the fix: 16 KiB chunk + 100 for the
     * extension message dictionary, which beats 16 KiB part + 9.
     */
    @Test
    public void testDefaultIsTheMagnetTerm() {
        PeerState.setPipelineParams(
                PeerState.MIN_PIPELINE, PeerState.MAX_PIPELINE, 16 * 1024, 128 * 1024);
        assertEquals(MagnetState.CHUNK_SIZE + 100, PeerConnectionIn.maxMessageSize());
    }

    /**
     * The bound tracks the live part size, not the value present at class load:
     * a 128 KiB part yields a 128 KiB + 9 bound, a 1 MiB part yields 1 MiB + 9.
     */
    @Test
    public void testTracksLivePartsize() {
        PeerState.PARTSIZE = 128 * 1024;
        assertEquals(128 * 1024 + 9, PeerConnectionIn.maxMessageSize());
        PeerState.PARTSIZE = 1024 * 1024;
        assertEquals(1024 * 1024 + 9, PeerConnectionIn.maxMessageSize());
    }

    /**
     * The whole point of the fix: a peer's piece message is exactly the block we
     * asked for, at most {@link PeerState#PARTSIZE} plus the 9-byte header, so it
     * must always fit under the bound at every part size the config allows.
     */
    @Test
    public void testAlwaysCoversOurOwnRequests() {
        for (int partsize : new int[] {1024, 4096, 16 * 1024, 32 * 1024, 64 * 1024, 128 * 1024,
                                        256 * 1024, 512 * 1024, 1024 * 1024}) {
            PeerState.PARTSIZE = partsize;
            int reply = partsize + 9;
            assertTrue("part size " + partsize + " reply " + reply + " over bound "
                       + PeerConnectionIn.maxMessageSize(),
                       reply <= PeerConnectionIn.maxMessageSize());
        }
    }

    /**
     * The smallest part size the setter allows (1 KiB) still accepts a magnet
     * chunk, so raising the part size can never lower the bound below the
     * extension message the magnet state downloads.
     */
    @Test
    public void testNeverBelowTheMagnetChunk() {
        PeerState.PARTSIZE = 1024;
        assertEquals(MagnetState.CHUNK_SIZE + 100, PeerConnectionIn.maxMessageSize());
        for (int partsize : new int[] {1024, 2048, 8192, 16 * 1024, 1024 * 1024}) {
            PeerState.PARTSIZE = partsize;
            assertTrue(PeerConnectionIn.maxMessageSize() >= MagnetState.CHUNK_SIZE + 100);
        }
    }

    /**
     * The bound follows the part size, not the larger per-request cap used to
     * validate requests peers send to us: we only ever ask for PARTSIZE, so a
     * larger MAX_PARTSIZE must not widen what we accept inbound.
     */
    @Test
    public void testFollowsPartsizeNotMaxPartsize() {
        PeerState.setPipelineParams(1, 12, 64 * 1024, 1024 * 1024);
        assertEquals(64 * 1024, PeerState.PARTSIZE);
        assertEquals(1024 * 1024, PeerState.MAX_PARTSIZE);
        assertEquals(64 * 1024 + 9, PeerConnectionIn.maxMessageSize());
    }

    /**
     * The boundary is exact, not padded: the bound is the largest piece message
     * we can have asked for, so raising the part size widens the guard by exactly
     * the part size and keeps it tight against a flood.
     */
    @Test
    public void testBoundaryIsExact() {
        PeerState.PARTSIZE = 256 * 1024;
        assertEquals(256 * 1024 + 9, PeerConnectionIn.maxMessageSize());
        PeerState.PARTSIZE = 256 * 1024 + 1;
        assertEquals(256 * 1024 + 10, PeerConnectionIn.maxMessageSize());
    }
}
