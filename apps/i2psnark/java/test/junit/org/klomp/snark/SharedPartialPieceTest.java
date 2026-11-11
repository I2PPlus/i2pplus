package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Tests for the shared partial piece: when more than one peer is fetching the same
 * piece they now work on one PartialPiece and are handed different runs of sub-blocks,
 * rather than each pulling a duplicate copy of the whole piece. The end game, where the
 * last few pieces are all in flight with several peers, is where that costs the most, so
 * that is the path exercised here.
 *
 * <p>The coordinator is built over a one-piece torrent with a Storage that has nothing
 * on disk, and the peers are socketless, so no messages are sent.
 *
 * @since 0.9.72+
 */
public class SharedPartialPieceTest {

    /** Piece length; four PARTSIZE requests wide, so peers can split it. */
    private static final int PIECE_LENGTH = 65536;

    /** One piece, so every peer after the first must share it. */
    private static final int PIECES = 1;

    /** Request size the piece is split into. */
    private static final int PART = PeerState.PARTSIZE;

    /**
     * The bencoded torrent, with the given number of pieces.
     *
     * @param pieces how many pieces the torrent has
     * @return the torrent bytes
     */
    private static byte[] torrentBytes(int pieces) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('d');
        sb.append("8:announce");
        sb.append("19:http://tracker.test");
        sb.append("4:info");
        sb.append('d');
        sb.append("6:length").append('i').append((long) PIECE_LENGTH * pieces).append('e');
        sb.append("4:name").append("9:test.file");
        sb.append("12:piece length").append('i').append(PIECE_LENGTH).append('e');
        sb.append("6:pieces").append(20 * pieces).append(':');
        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] hashes = new byte[20 * pieces];
        byte[] tail = "ee".getBytes(StandardCharsets.ISO_8859_1);
        byte[] rv = new byte[head.length + hashes.length + tail.length];
        System.arraycopy(head, 0, rv, 0, head.length);
        System.arraycopy(hashes, 0, rv, head.length, hashes.length);
        System.arraycopy(tail, 0, rv, head.length + hashes.length, tail.length);
        return rv;
    }

    /**
     * A socketless peer with a distinct id, so the coordinator can tell the peers apart.
     *
     * @param n the peer number, used as its id
     * @param pieces how many pieces the torrent it belongs to has
     * @return a peer with no socket
     * @throws Throwable on peer id construction failure
     */
    private static Peer peer(int n, int pieces) throws Throwable {
        byte[] id = new byte[32];
        id[0] = (byte) n;
        PeerID pid = new PeerID(id, (I2PSnarkUtil) null);
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(torrentBytes(pieces)));
        return new Peer(pid, new byte[20], mi.getInfoHash(), mi);
    }

    /**
     * A socketless peer for the one-piece torrent.
     *
     * @param n the peer number, used as its id
     * @return a peer with no socket
     * @throws Throwable on peer id construction failure
     */
    private static Peer peer(int n) throws Throwable {
        return peer(n, PIECES);
    }

    /**
     * A coordinator over a torrent with nothing on disk, so every piece is wanted.
     *
     * @param util the snark util
     * @param pieces how many pieces the torrent has
     * @return the coordinator, to be halted by the caller
     * @throws Throwable on metainfo failure
     */
    private static PeerCoordinator coordinator(I2PSnarkUtil util, int pieces) throws Throwable {
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(torrentBytes(pieces)));
        Storage st = new Storage(util, new File("/tmp/snark-shared-test"), mi, null, false);
        return new PeerCoordinator(
                util, new byte[20], mi.getInfoHash(), mi, st, null, null, new NullBWL());
    }

    /**
     * A coordinator over the one-piece torrent.
     *
     * @param util the snark util
     * @return the coordinator, to be halted by the caller
     * @throws Throwable on metainfo failure
     */
    private static PeerCoordinator coordinator(I2PSnarkUtil util) throws Throwable {
        return coordinator(util, PIECES);
    }

    /**
     * A peer that has every piece.
     *
     * @param pieces how many pieces the torrent has
     * @return an all-ones bitfield for the test torrent
     */
    private static BitField hasAll(int pieces) {
        BitField bf = new BitField(pieces);
        bf.setAll();
        return bf;
    }

    /**
     * A peer that has every piece of the one-piece torrent.
     *
     * @return an all-ones bitfield
     */
    private static BitField hasAll() {
        return hasAll(PIECES);
    }

    /**
     * A fresh util on the global context.
     *
     * @return the util
     */
    private static I2PSnarkUtil newUtil() {
        return new I2PSnarkUtil(net.i2p.I2PAppContext.getGlobalContext());
    }

    /**
     * Counts nothing; PartialPiece.read() only needs a listener to report the bytes to.
     */
    private static class NullBWL implements BandwidthListener {

        @Override
        public long getUploadRate() {
            return 0;
        }

        @Override
        public long getDownloadRate() {
            return 0;
        }

        @Override
        public void uploaded(int size) { /* no-op */ }

        @Override
        public void downloaded(int size) { /* no-op */ }

        @Override
        public boolean shouldSend(int size) {
            return true;
        }

        @Override
        public boolean shouldRequest(Peer peer, int size) {
            return true;
        }

        @Override
        public long getUpBWLimit() {
            return Long.MAX_VALUE;
        }

        @Override
        public long getDownBWLimit() {
            return Long.MAX_VALUE;
        }

        @Override
        public boolean overUpBWLimit() {
            return false;
        }

        @Override
        public boolean overDownBWLimit() {
            return false;
        }
    }

    /**
     * A second peer is given a piece of its own while one is still unclaimed, so the
     * sharing does not disturb normal rarest-first piece selection.
     */
    @Test
    public void testPeersGetSeparatePiecesWhileAnyRemain() throws Throwable {
        PeerCoordinator pc = coordinator(newUtil(), 2);
        try {
            PartialPiece one = pc.getPartialPiece(peer(1, 2), hasAll(2));
            PartialPiece two = pc.getPartialPiece(peer(2, 2), hasAll(2));
            assertNotNull(one);
            assertNotNull(two);
            assertNotSame(one, two);
            assertNotEquals(one.getPiece(), two.getPiece());
            assertEquals(2, pc.livePartialCount());
        } finally {
            pc.halt();
        }
    }

    /**
     * Once every piece is in flight, the next peer is given the same PartialPiece the
     * others are filling, not a second copy of it.
     */
    @Test
    public void testPeerSharesThePieceAlreadyInFlight() throws Throwable {
        PeerCoordinator pc = coordinator(newUtil());
        try {
            PartialPiece first = pc.getPartialPiece(peer(1), hasAll());
            assertNotNull(first);
            PartialPiece second = pc.getPartialPiece(peer(2), hasAll());
            assertSame(first, second);
            // one piece, one PartialPiece, however many peers are pulling it
            assertEquals(1, pc.livePartialCount());
        } finally {
            pc.halt();
        }
    }

    /**
     * Peers sharing a piece are handed different blocks of it: each getRequest() skips
     * what the others have already been asked for.
     */
    @Test
    public void testSharingPeersGetDifferentBlocks() throws Throwable {
        PeerCoordinator pc = coordinator(newUtil());
        try {
            PartialPiece pp = pc.getPartialPiece(peer(1), hasAll());
            Request one = pp.getRequest();
            Request two = pp.getRequest();
            Request three = pp.getRequest();
            Request four = pp.getRequest();
            assertNotNull(one);
            assertNotNull(two);
            assertNotNull(three);
            assertNotNull(four);
            assertEquals(0, one.off);
            assertEquals(PART, two.off);
            assertEquals(2 * PART, three.off);
            assertEquals(3 * PART, four.off);
            // everything is now here or claimed, so nobody else can join in
            assertNull(pp.getRequest());
            assertTrue(pp.isFullyClaimed());
            assertNull(pc.getPartialPiece(peer(3), hasAll()));
        } finally {
            pc.halt();
        }
    }

    /**
     * When a peer goes away its blocks are claimed again by whoever asks, and the piece
     * stays live while another peer is still pulling it.
     */
    @Test
    public void testLeavingPeerFreesItsBlocks() throws Throwable {
        PeerCoordinator pc = coordinator(newUtil());
        try {
            Peer one = peer(1);
            Peer two = peer(2);
            PartialPiece pp = pc.getPartialPiece(one, hasAll());
            Request first = pp.getRequest();
            PartialPiece shared = pc.getPartialPiece(two, hasAll());
            assertSame(pp, shared);
            Request second = shared.getRequest();
            assertEquals(PART, second.off);

            // peer one goes away
            pc.savePartialPieces(one, Collections.singletonList(first));
            assertEquals(1, pc.livePartialCount());
            assertEquals(0, pc.orphanPartialCount());

            // its block is available again, and is handed out before the ones still in flight
            Request again = pp.getRequest();
            assertEquals(0, again.off);
            assertSame(pp, again.getPartialPiece());
        } finally {
            pc.halt();
        }
    }

    /**
     * When the last peer fetching a shared piece goes away, the piece is parked as an
     * orphan so the blocks already downloaded are not lost, and it stops being shared so
     * the orphan list owns it.
     */
    @Test
    public void testLastPeerLeavingParksThePiece() throws Throwable {
        PeerCoordinator pc = coordinator(newUtil());
        try {
            Peer one = peer(1);
            Peer two = peer(2);
            PartialPiece pp = pc.getPartialPiece(one, hasAll());
            Request first = pp.getRequest();
            PartialPiece shared = pc.getPartialPiece(two, hasAll());
            Request second = shared.getRequest();

            pc.savePartialPieces(one, Collections.singletonList(first));
            pc.savePartialPieces(two, Collections.singletonList(second));

            assertEquals(0, pc.livePartialCount());
            assertEquals(1, pc.orphanPartialCount());
            assertFalse(pp.isShared());
            // the orphan starts its scan over, with no claims left over from the peers
            // that went away
            Request next = pp.getRequest();
            assertNotNull(next);
            assertEquals(0, next.off);
        } finally {
            pc.halt();
        }
    }

    /**
     * A peer pulling several blocks of one piece hands back several requests for the
     * same PartialPiece. It must be put away once: the second request used to fall
     * through to the "not bigger than what is already there" branch and release the copy
     * that had just been kept, deleting the temp file of a piece still in the list.
     */
    @Test
    public void testSeveralRequestsForOnePieceAreSettledOnce() throws Throwable {
        PeerCoordinator pc = coordinator(newUtil());
        try {
            Peer one = peer(1);
            PartialPiece pp = pc.getPartialPiece(one, hasAll());
            Request a = pp.getRequest();
            Request b = pp.getRequest();
            List<Request> reqs = new ArrayList<>(2);
            reqs.add(a);
            reqs.add(b);
            pc.savePartialPieces(one, reqs);
            assertEquals(0, pc.livePartialCount());
            assertEquals(1, pc.orphanPartialCount());
            // the orphan is intact and starts its scan over
            Request next = pp.getRequest();
            assertNotNull(next);
            assertEquals(0, next.off);
        } finally {
            pc.halt();
        }
    }

    /**
     * A block is no longer handed out once its bytes have arrived, and clearRequested()
     * puts it back in play.
     */
    @Test
    public void testClaimsFollowTheBytes() {
        PartialPiece pp = new PartialPiece(new Piece(0), PIECE_LENGTH, new File("/tmp"));
        Request first = pp.getRequest();
        assertEquals(0, first.off);
        // the bytes for the first sub-block land, but the rest of that request is
        // still claimed, so the scan moves past the whole of it
        pp.markSubBlock(0);
        Request next = pp.getRequest();
        assertEquals(PART, next.off);
        // letting go of the request puts the unfilled part of it back in play, after
        // the sub-block whose bytes already arrived
        pp.clearRequested(first.off, first.len);
        Request back = pp.getRequest();
        assertEquals(PART / 4, back.off);
    }

    /**
     * A shared piece is not freed by a peer letting go of it, only by the coordinator
     * that owns it. The temp file behind a piece too big for memory is the observable.
     */
    @Test
    public void testSharedPieceSurvivesPeerRelease() throws IOException {
        int len = 2 * 1024 * 1024; // over the in-memory limit, so it uses a temp file
        PartialPiece pp = new PartialPiece(new Piece(0), len, new File(System.getProperty("java.io.tmpdir")));
        byte[] data = new byte[PeerState.PARTSIZE];
        pp.read(new DataInputStream(new ByteArrayInputStream(data)), 0, data.length, new NullBWL());
        pp.setShared(true);
        pp.release();
        assertNotNull(pp.getHash());
        pp.setShared(false);
        pp.release();
        try {
            pp.getHash();
            fail("the temp file should be gone once the owner lets go");
        } catch (IOException e) {
            // expected
        }
    }

    /**
     * A non-shared piece is still freed by the first release(), as it always was.
     */
    @Test
    public void testUnsharedPieceFreesOnRelease() throws IOException {
        int len = 2 * 1024 * 1024;
        PartialPiece pp = new PartialPiece(new Piece(0), len, new File(System.getProperty("java.io.tmpdir")));
        byte[] data = new byte[PeerState.PARTSIZE];
        pp.read(new DataInputStream(new ByteArrayInputStream(data)), 0, data.length, new NullBWL());
        pp.release();
        try {
            pp.getHash();
            fail("the temp file should be gone");
        } catch (IOException e) {
            // expected
        }
        // and writing it out after that is the failure a stale handle would show
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            pp.write(new java.io.DataOutputStream(out), 0, data.length);
            fail("writing a released piece should fail");
        } catch (IOException e) {
            // expected
        }
    }
}
