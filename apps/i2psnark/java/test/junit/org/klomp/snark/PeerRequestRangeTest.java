package org.klomp.snark;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.Test;

/**
 * Pins that a requested block has to fit inside the piece it names.
 *
 * <p>The request gate checked that the block starts inside the piece and that its
 * length is under the part cap, but never that it ends there. A peer asking for
 * <code>begin = pieceLength - 1, length = 16384</code> passed every clause, and the
 * storage layer then positions at the absolute torrent offset and reads that many
 * bytes: on a middle piece we hand over bytes of the neighbouring pieces, and on the
 * final piece - short in almost every torrent - the read walks off the end and the
 * storage layer throws, which costs an ERROR with a stack trace, a console message
 * per request, or a disconnect.
 *
 * @since 0.9.72+
 */
public class PeerRequestRangeTest {

    /** Piece length of the fixture torrent. */
    private static final int PIECE_LEN = 16384;

    /** Total length of the fixture torrent, which leaves the last piece short. */
    private static final long TOTAL_LEN = 30000L;

    /** Bytes in the final piece of the fixture torrent. */
    private static final int LAST_LEN = (int) (TOTAL_LEN - PIECE_LEN);

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public PeerRequestRangeTest() {}

    /**
     * An unchoked peer state on the two-piece fixture torrent, asking through the
     * given connection. Unchoked so the request reaches the range check rather than
     * the choke guard.
     *
     * @param out the connection the accepted block would be queued on
     * @return a state ready to take a request
     * @throws Throwable on a bencode error in the fixture
     */
    private static PeerState stateFor(PeerConnectionOut out) throws Throwable {
        MetaInfo mi =
            new MetaInfo(new ByteArrayInputStream(PeerStateTest.buildTorrentBytes(new byte[40])));
        PeerID pid = new PeerID(new byte[32], (I2PSnarkUtil) null);
        Peer peer = new Peer(pid, new byte[20], mi.getInfoHash(), mi);
        PeerState ps = new PeerState(peer, null, null, mi, null, out);
        peer.state = ps;
        ps.choking = false;
        return ps;
    }

    /**
     * A connection that accepts everything queued to it, so the only reason a block
     * does not reach it is the gate under test.
     *
     * @return a mocked send connection with an empty queue
     */
    private static PeerConnectionOut queue() {
        PeerConnectionOut out = mock(PeerConnectionOut.class);
        when(out.queuedBytes()).thenReturn(0);
        return out;
    }

    /**
     * The defect: one byte past the end of the piece. The start is inside, the length
     * is under the cap, and the read walks into the next piece.
     *
     * @throws Throwable on a bencode error in the fixture
     */
    @Test
    public void testABlockPastThePieceEndIsRefused() throws Throwable {
        PeerConnectionOut out = queue();
        PeerState ps = stateFor(out);
        ps.requestMessage(0, PIECE_LEN - 1, 2);
        verify(out, never()).sendPiece(anyInt(), anyInt(), anyInt(), any(DataLoader.class));
    }

    /**
     * The same request with the last byte of the piece is served: the gate refuses
     * overruns, not blocks that end exactly on the piece boundary.
     *
     * @throws Throwable on a bencode error in the fixture
     */
    @Test
    public void testABlockEndingOnThePieceEndIsServed() throws Throwable {
        PeerConnectionOut out = queue();
        PeerState ps = stateFor(out);
        ps.requestMessage(0, PIECE_LEN - 1, 1);
        verify(out).sendPiece(0, PIECE_LEN - 1, 1, ps);
    }

    /**
     * A whole block that starts inside a piece and ends past it is refused too - this
     * is the shape that served bytes belonging to the following pieces.
     *
     * @throws Throwable on a bencode error in the fixture
     */
    @Test
    public void testABlockRunningIntoTheNextPieceIsRefused() throws Throwable {
        PeerConnectionOut out = queue();
        PeerState ps = stateFor(out);
        ps.requestMessage(0, 16000, 1000);
        verify(out, never()).sendPiece(anyInt(), anyInt(), anyInt(), any(DataLoader.class));
    }

    /**
     * The final piece is short, so a block that fits the nominal piece length runs off
     * the end of the torrent, which is where the storage layer throws.
     *
     * @throws Throwable on a bencode error in the fixture
     */
    @Test
    public void testABlockPastTheEndOfTheTorrentIsRefused() throws Throwable {
        PeerConnectionOut out = queue();
        PeerState ps = stateFor(out);
        ps.requestMessage(1, LAST_LEN - 1, 2);
        ps.requestMessage(1, 0, LAST_LEN + 1);
        verify(out, never()).sendPiece(anyInt(), anyInt(), anyInt(), any(DataLoader.class));
    }

    /**
     * A block covering the whole short final piece is served, so the gate does not
     * refuse the last piece's legitimate tail reads.
     *
     * @throws Throwable on a bencode error in the fixture
     */
    @Test
    public void testABlockFillingTheLastPieceIsServed() throws Throwable {
        PeerConnectionOut out = queue();
        PeerState ps = stateFor(out);
        ps.requestMessage(1, 0, LAST_LEN);
        verify(out).sendPiece(1, 0, LAST_LEN, ps);
    }
}
