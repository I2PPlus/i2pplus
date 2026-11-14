package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for how the end game is detected: by what the picker ran into, not by how many pieces
 * are left. A count of wanted pieces stood in for the condition "this peer can only be kept
 * busy by duplicating a request", and it was wrong in both directions - it counted pieces the
 * user disabled as work still to do, and it called a torrent of any size nearly finished as soon
 * as the threshold of pieces happened to be left.
 *
 * @since 0.9.72+
 */
public class EndGameDetectionTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public EndGameDetectionTest() {}

    /**
     * A pick that found a piece for the peer ends the end game, whatever else the torrent has
     * left to fetch.
     */
    @Test
    public void testAPickThatFoundWorkEndsTheEndGame() {
        assertFalse(PeerCoordinator.endGameAfterPick(true, 1));
        assertFalse(PeerCoordinator.endGameAfterPick(true, PeerCoordinator.END_GAME_THRESHOLD));
        assertFalse(PeerCoordinator.endGameAfterPick(true, 5000));
    }

    /**
     * A pick that found nothing, while pieces are still wanted, is the end game: the only way to
     * keep that peer working is to ask it for a piece another peer is already pulling. The count
     * no longer decides, so a torrent one piece past the old threshold reaches it too.
     */
    @Test
    public void testAPickThatFoundNothingIsTheEndGame() {
        int at = PeerCoordinator.END_GAME_THRESHOLD;
        assertTrue(PeerCoordinator.endGameAfterPick(false, 1));
        assertTrue(PeerCoordinator.endGameAfterPick(false, at));
        assertTrue(PeerCoordinator.endGameAfterPick(false, at + 1));
        assertTrue(PeerCoordinator.endGameAfterPick(false, 5000));
    }

    /**
     * A torrent that wants nothing is finished, not finishing - nothing is in flight to race for,
     * so a peer that is turned away is simply turned away.
     */
    @Test
    public void testATorrentThatWantsNothingIsNotInTheEndGame() {
        assertFalse(PeerCoordinator.endGameAfterPick(false, 0));
        assertFalse(PeerCoordinator.endGameAfterPick(true, 0));
    }
}
