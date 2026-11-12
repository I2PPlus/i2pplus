package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.File;

import org.junit.Test;

/**
 * Pins the three pure decisions behind the end-game choke and interest policy: keeping a
 * peer that holds the last pieces unchoked, judging an uploader by its whole rate window
 * rather than one empty pass, and not giving up on a peer that still owes us blocks. All
 * three were reached by a nearly complete download that stalled at one piece left while
 * every seed in the swarm was marked uninteresting.
 *
 * @since 0.9.72+
 */
public class EndgameChokeDecisionTest {

    /** Piece length for the in-flight request used by the interest decision. */
    private static final int PIECE_LENGTH = 65536;

    /**
     * Test instance; every test calls a static decision method, so the instance carries
     * no state.
     */
    public EndgameChokeDecisionTest() {
    }

    /** A peer that holds a wanted piece during the end game is not a choke candidate. */
    @Test
    public void testAnEndgameSourceIsSpared() {
        assertTrue(PeerCheckerTask.spareEndgameSource(true, true, false));
    }

    /**
     * With a third of the torrent still to fetch there are plenty of sources, so
     * tit-for-tat works and should.
     */
    @Test
    public void testTheSpareOnlyAppliesWhileTheEndGameIsOn() {
        assertFalse(PeerCheckerTask.spareEndgameSource(false, true, false));
    }

    /** A peer whose bitfield covers nothing we want is worth no slots at all. */
    @Test
    public void testAPeerWithoutWhatWeNeedIsNotSpared() {
        assertFalse(PeerCheckerTask.spareEndgameSource(true, false, false));
    }

    /** The upload bandwidth cap is a hard limit on what the router can send, not a preference. */
    @Test
    public void testTheUploadBandwidthLimitOutranksTheSpare() {
        assertFalse(PeerCheckerTask.spareEndgameSource(true, true, true));
    }

    /**
     * One empty check period is not a verdict - a peer filling one block of a piece
     * shared with other peers can go several passes without sending us anything.
     */
    @Test
    public void testAnUploaderIsJudgedOnItsWholeRateWindow() {
        assertFalse(PeerCheckerTask.uploaderNotDelivering(0, 4096));
        assertFalse(PeerCheckerTask.uploaderNotDelivering(1024, 0));
        assertTrue(PeerCheckerTask.uploaderNotDelivering(0, 0));
    }

    /**
     * Saying we are uninterested stops a peer sending, so it is only safe once nothing of
     * ours is in flight with it.
     */
    @Test
    public void testWeDoNotGiveUpOnAPeerThatOwesUsBlocks() {
        Request inFlight = new Request(
                new PartialPiece(new Piece(0), PIECE_LENGTH, new File("/tmp")),
                0,
                PeerState.PARTSIZE);
        // requests still in flight: the peer stops sending the moment we say uninterested
        assertFalse(PeerState.shouldGiveUpOnPeer(true, null, true));
        // still working through a piece of its own
        assertFalse(PeerState.shouldGiveUpOnPeer(true, inFlight, false));
        // already uninterested, nothing to do
        assertFalse(PeerState.shouldGiveUpOnPeer(false, null, false));
        // nothing in flight and no current piece: give up on it
        assertTrue(PeerState.shouldGiveUpOnPeer(true, null, false));
    }
}
