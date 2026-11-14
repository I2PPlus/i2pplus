package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import org.junit.Test;

/**
 * Tests for PeerState.setChoking and the 200 ms spacing guard on choke-state messages. The guard
 * exists so a peer is not told it is unchoked and choked again inside a few hundred
 * milliseconds, and it used to wrap the state write as well: a decision landing inside the
 * window was dropped entirely, while the checker had already adjusted its uploader counters for
 * it, so the counters, the field and the wire disagreed until a later pass resynced them. The
 * state now moves on every decision and only the message waits, with a held-back message
 * resolved by the next call for that peer once the spacing allows.
 *
 * <p>The connections here do not start their sender thread, so a queued message stays queued
 * and the queue is what the assertions read.
 *
 * @since 0.9.72+
 */
public class ChokeStateTest {

    /** The base time the fixtures pass as "now", far from zero and from the real clock. */
    private static final long T0 = 1_000_000L;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public ChokeStateTest() {}

    /**
     * A peer state on the shared two-piece test torrent, choking, with a connection whose sender
     * thread is not running.
     *
     * @return a fresh peer state and its send connection
     * @throws Throwable on torrent parse or peer id construction failure
     */
    private static PeerState stateFor() throws Throwable {
        MetaInfo mi =
                new MetaInfo(
                        new java.io.ByteArrayInputStream(
                                PeerStateTest.buildTorrentBytes(new byte[40])));
        PeerID pid = new PeerID(new byte[32], (I2PSnarkUtil) null);
        Peer peer = new Peer(pid, new byte[20], mi.getInfoHash(), mi);
        PeerConnectionOut out =
                new PeerConnectionOut(peer, new DataOutputStream(new ByteArrayOutputStream()));
        return new PeerState(peer, null, null, mi, null, out);
    }

    /**
     * A decision that lands inside the spacing window still changes our state, and sends nothing.
     * The checker has already counted this peer out of an upload slot by the time it asks, so a
     * state left behind is a counter that no longer describes the peer.
     *
     * @throws Throwable if the test torrent or the peer fixture cannot be built
     */
    @Test
    public void testStateMovesWhenTheMessageIsHeldBack() throws Throwable {
        PeerState ps = stateFor();
        assertTrue(ps.choking);
        ps.setChoking(false, T0);
        assertFalse(ps.choking);
        assertEquals(1, ps.out.queuedCount(Message.UNCHOKE));

        // 5 ms after the unchoke, inside the window
        ps.setChoking(true, T0 + 5);
        assertTrue("the choke decision was dropped", ps.choking);
        assertEquals(0, ps.out.queuedCount(Message.CHOKE));
    }

    /**
     * A message the window held back is not lost: the next call for that peer resolves it once
     * the spacing allows. Because the earlier unchoke was still sitting in the send queue, never
     * written, resolving means cancelling it - the peer is told neither state, which is all it
     * needs to know about a flip that finished before anything went out.
     *
     * @throws Throwable if the test torrent or the peer fixture cannot be built
     */
    @Test
    public void testHeldMessageResolvesAgainstTheQueuedInverse() throws Throwable {
        PeerState ps = stateFor();
        ps.setChoking(false, T0);
        ps.setChoking(true, T0 + 5);
        assertEquals(0, ps.out.queuedCount(Message.CHOKE));

        ps.setChoking(true, T0 + 205);
        assertTrue(ps.choking);
        assertEquals(0, ps.out.queuedCount(Message.CHOKE));
        assertEquals("the unchoke it replaces is dropped from the queue",
                     0, ps.out.queuedCount(Message.UNCHOKE));
    }

    /**
     * The window is measured from the last message sent, and a decision at exactly its end is
     * resolved rather than held.
     *
     * @throws Throwable if the test torrent or the peer fixture cannot be built
     */
    @Test
    public void testSpacingIsMeasuredFromTheLastMessage() throws Throwable {
        PeerState ps = stateFor();
        ps.setChoking(false, T0);
        ps.setChoking(true, T0 + 199);
        assertEquals(0, ps.out.queuedCount(Message.CHOKE));
        ps.setChoking(true, T0 + 199);
        assertEquals(0, ps.out.queuedCount(Message.CHOKE));
        assertEquals(1, ps.out.queuedCount(Message.UNCHOKE));
        ps.setChoking(true, T0 + 200);
        assertEquals(0, ps.out.queuedCount(Message.UNCHOKE));
    }

    /**
     * Asking for the state the peer is already in sends nothing, however far apart the calls are,
     * and does not flush a message that is still waiting for its window.
     *
     * @throws Throwable if the test torrent or the peer fixture cannot be built
     */
    @Test
    public void testNoMessageForAStateWeAlreadyHold() throws Throwable {
        PeerState ps = stateFor();
        ps.setChoking(false, T0);
        ps.setChoking(false, T0 + 5);
        assertEquals(1, ps.out.queuedCount(Message.UNCHOKE));

        // a change inside the window is held back, and a no-change call inside the window
        // must not flush it early
        ps.setChoking(true, T0 + 6);
        ps.setChoking(true, T0 + 7);
        assertEquals(0, ps.out.queuedCount(Message.CHOKE));
    }
}
