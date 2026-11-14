package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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

    /** A piece too big to keep in memory, so its temp file can be watched. */
    private static final int DISK_PIECE = 2 * 1024 * 1024;

    /**
     * Test instance; every test builds its own coordinator and peers, so the instance
     * carries no state between them.
     */
    public SharedPartialPieceTest() {
    }

    /**
     * The bencoded torrent, with the given number of pieces.
     *
     * @param pieces how many pieces the torrent has
     * @return the torrent bytes
     */
    private static byte[] torrentBytes(int pieces) {
        return torrentBytes(pieces, PIECE_LENGTH);
    }

    /**
     * The bencoded torrent, with the given number of pieces of the given length.
     *
     * @param pieces how many pieces the torrent has
     * @param pieceLength the piece length in bytes
     * @return the torrent bytes
     */
    private static byte[] torrentBytes(int pieces, int pieceLength) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('d');
        sb.append("8:announce");
        sb.append("19:http://tracker.test");
        sb.append("4:info");
        sb.append('d');
        sb.append("6:length").append('i').append((long) pieceLength * pieces).append('e');
        sb.append("4:name").append("9:test.file");
        sb.append("12:piece length").append('i').append(pieceLength).append('e');
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
     * @throws Throwable on metainfo or storage failure
     */
    private static PeerCoordinator coordinator(I2PSnarkUtil util, int pieces) throws Throwable {
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(torrentBytes(pieces)));
        Storage st = new Storage(util, new File("/tmp/snark-shared-test"), mi, null, false);
        return new PeerCoordinator(
                util, new byte[20], mi.getInfoHash(), mi, st, null, null, new NullBWL());
    }

    /**
     * A coordinator over a torrent with the given number of pieces of the given length,
     * for the cases where the piece's block count is what is being tested.
     *
     * @param util the snark util
     * @param pieces how many pieces the torrent has
     * @param pieceLength the piece length in bytes
     * @return the coordinator, to be halted by the caller
     * @throws Throwable on metainfo or storage failure
     */
    private static PeerCoordinator coordinator(I2PSnarkUtil util, int pieces, int pieceLength)
            throws Throwable {
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(torrentBytes(pieces, pieceLength)));
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
     * @return a util with no torrent loaded, for the coordinator's constructor
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
     * @throws Throwable if the torrent fixture or the coordinator setup fails
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
     * @throws Throwable if the torrent fixture or the coordinator setup fails
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
     * @throws Throwable if the torrent fixture or the coordinator setup fails
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
     * @throws Throwable if the torrent fixture or the coordinator setup fails
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
     * @throws Throwable if the torrent fixture or the coordinator setup fails
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
     * @throws Throwable if the torrent fixture or the coordinator setup fails
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
     * @throws IOException if the piece's temp file cannot be read or written
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
     *
     * @throws IOException if the piece's temp file cannot be read or written
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

    /**
     * A peer state wired to the coordinator over a throwaway output stream, so requests
     * are built and dropped without a socket.
     *
     * @param pc the coordinator that owns the piece records
     * @param one the peer the state belongs to
     * @return the state, also installed as the peer's own
     * @throws Throwable on metainfo failure
     */
    private static PeerState peerState(PeerCoordinator pc, Peer one) throws Throwable {
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(torrentBytes(PIECES)));
        PeerState st = new PeerState(one, pc, new NullBWL(), mi, null,
                                     new PeerConnectionOut(
                                             one, new DataOutputStream(new ByteArrayOutputStream())));
        one.state = st;
        return st;
    }

    /**
     * The stall clock starts when a piece first gets a requester, and the end game's
     * repeated offers of that same piece to other peers do not refresh it. A refresh
     * there is what let a piece that had fetched nothing for an hour and a half look
     * forever fresh to the five-minute re-queue.
     * @throws Throwable if the peer id or the torrent fixture fails to build
     */
    @Test
    public void testEndgameSweepsDoNotRefreshTheStallClock() throws Throwable {
        Piece p = new Piece(0);
        p._lastActive = 1000L;
        p.setRequested(peer(1).getPeerID(), true);
        long started = p.getLastActive();
        assertTrue(started > 1000L);
        // a sentinel past the start time: any refresh puts the clock back to now
        p._lastActive = started + 60_000L;
        // each end-game offer marks the peer as a requester before backing out because
        // the piece's blocks are all claimed already
        p.setRequested(peer(2).getPeerID(), true);
        p.setRequested(peer(3).getPeerID(), true);
        assertEquals(started + 60_000L, p.getLastActive());
        assertTrue(p.isRequested());
    }

    /**
     * The clock restarts when a piece goes back to having no requester at all.
     *
     * @throws Throwable if the peer id fails to build
     */
    @Test
    public void testStallClockRestartsWhenThePieceIsAskedForAgain() throws Throwable {
        Piece p = new Piece(0);
        p.setRequested(peer(1).getPeerID(), true);
        p.setRequested(peer(1).getPeerID(), false);
        assertFalse(p.isRequested());
        p._lastActive = 1000L;
        p.setRequested(peer(2).getPeerID(), true);
        assertTrue(p.getLastActive() > 1000L);
    }

    /**
     * A piece that is requested and has fetched nothing for the timeout is still seen as
     * stalled after the end game has offered it to several peers.
     * @throws Throwable if the peer id fails to build
     */
    @Test
    public void testStalledPieceIsStillDetectedAfterTheEndgameSweeps() throws Throwable {
        Piece p = new Piece(0);
        p.setRequested(peer(1).getPeerID(), true);
        p._lastActive = System.currentTimeMillis() - PeerCoordinator.PIECE_STALL_TIMEOUT - 1;
        p.setRequested(peer(2).getPeerID(), true);
        p.setRequested(peer(3).getPeerID(), true);
        long now = System.currentTimeMillis();
        assertTrue(PeerCoordinator.isStalled(p, now, PeerCoordinator.PIECE_STALL_TIMEOUT));
    }

    /**
     * A live partial that no registered requester is fetching is invisible to piece
     * selection and to the sharing path, so the checker sweep parks it as an orphan with
     * its blocks back in the pool.
     * @throws Throwable if the torrent fixture or the coordinator setup fails
     */
    @Test
    public void testLivePartialWithNoRequesterIsReQueued() throws Throwable {
        PeerCoordinator pc = coordinator(newUtil());
        try {
            Peer one = peer(1);
            PartialPiece pp = pc.getPartialPiece(one, hasAll());
            assertNotNull(pp.getRequest());
            // the peer that was pulling it is gone, and its blocks were never handed back
            pc.wantedPiece(0).setRequested(one.getPeerID(), false);
            // a checker pass some time after the stall timeout
            pc.checkStalledPieces(System.currentTimeMillis()
                                  + PeerCoordinator.PIECE_STALL_TIMEOUT + 60_000L);
            assertEquals(0, pc.livePartialCount());
            assertEquals(1, pc.orphanPartialCount());
            assertFalse(pp.isShared());
            // the blocks are in the pool again for whoever picks the orphan up
            Request again = pp.getRequest();
            assertNotNull(again);
            assertEquals(0, again.off);
        } finally {
            pc.halt();
        }
    }

    /**
     * The sweep leaves a live partial alone while a peer is still registered for it.
     *
     * @throws Throwable if the torrent fixture or the coordinator setup fails
     */
    @Test
    public void testLivePartialWithARequesterIsLeftAlone() throws Throwable {
        PeerCoordinator pc = coordinator(newUtil());
        try {
            pc.getPartialPiece(peer(1), hasAll());
            pc.checkStalledPieces(System.currentTimeMillis()
                                  + PeerCoordinator.PIECE_STALL_TIMEOUT + 60_000L);
            assertEquals(1, pc.livePartialCount());
            assertEquals(0, pc.orphanPartialCount());
        } finally {
            pc.halt();
        }
    }

    /**
     * The peer that hands back the last block it held of a piece re-queues it on the spot.
     * Waiting for the checker's five-minute stall backstop instead leaves a five-minute
     * hole in the end game, where one piece and a handful of peers are all that is left.
     *
     * @throws Throwable if the torrent fixture or the coordinator setup fails
     */
    @Test
    public void testLastRequesterHandingBackReQueuesThePieceAtOnce() throws Throwable {
        PeerCoordinator pc = coordinator(newUtil());
        try {
            Peer one = peer(1);
            PartialPiece pp = pc.getPartialPiece(one, hasAll());
            Request req = pp.getRequest();
            assertNotNull(req);
            pc.savePartialPieces(one, Collections.singletonList(req));
            assertEquals(0, pc.livePartialCount());
            assertEquals(1, pc.orphanPartialCount());
            // the blocks are in the pool again for whoever picks the orphan up
            Request again = pp.getRequest();
            assertNotNull(again);
            assertEquals(0, again.off);
        } finally {
            pc.halt();
        }
    }

    /**
     * A peer that goes away without handing its blocks back leaves a piece nobody is
     * fetching, and the disconnect path re-queues it at once rather than waiting out the
     * checker's five-minute backstop.
     *
     * @throws Throwable if the torrent fixture or the coordinator setup fails
     */
    @Test
    public void testADisconnectedPeerReQueuesThePieceAtOnce() throws Throwable {
        PeerCoordinator pc = coordinator(newUtil());
        try {
            Peer one = peer(1);
            PartialPiece pp = pc.getPartialPiece(one, hasAll());
            assertNotNull(pp.getRequest());
            pc.removePeerFromPieces(one);
            assertEquals(0, pc.livePartialCount());
            assertEquals(1, pc.orphanPartialCount());
        } finally {
            pc.halt();
        }
    }

    /**
     * Re-queued partials go through the same dedupe and size cap as the ones a peer hands
     * back, so a torrent cannot grow the orphan list past its connection count.
     *
     * @throws Throwable if the torrent fixture or the coordinator setup fails
     */
    @Test
    public void testReQueuedPartialsStayInsideTheOrphanCap() throws Throwable {
        PeerCoordinator pc = coordinator(newUtil(), 6);
        try {
            Peer[] peers = new Peer[6];
            List<Request> reqs = new ArrayList<>(6);
            for (int i = 0; i < peers.length; i++) {
                peers[i] = peer(i + 1, 6);
                PartialPiece pp = pc.getPartialPiece(peers[i], hasAll(6));
                Request req = pp.getRequest();
                assertNotNull(req);
                reqs.add(req);
            }
            assertEquals(6, pc.livePartialCount());
            for (int i = 0; i < peers.length; i++) {
                pc.savePartialPieces(peers[i], Collections.singletonList(reqs.get(i)));
            }
            // six pieces came to rest, the list holds at most four for this torrent
            assertEquals(0, pc.livePartialCount());
            assertEquals(4, pc.orphanPartialCount());
        } finally {
            pc.halt();
        }
    }

    /**
     * A peer that rejects one block of a piece it is still filling gives that block back
     * at once, so another peer can be asked for it while the rejecter keeps the rest.
     * Before the fix the rejected block stayed claimed by nobody, and in the end game
     * that is a piece that never finishes.
     * @throws Throwable if the torrent fixture or the coordinator setup fails
     */
    @Test
    public void testRejectedBlockIsFreedWhileOthersRemain() throws Throwable {
        PeerCoordinator pc = coordinator(newUtil());
        try {
            Peer one = peer(1);
            PeerState st = peerState(pc, one);
            st.bitfield = hasAll();
            st.choked = false;
            st.interesting = true;
            // two blocks of the one piece in flight with it
            st.addRequest();
            st.addRequest();
            // the same live copy, shared out to a second peer, so the test can see it
            PartialPiece pp = pc.getPartialPiece(peer(2), hasAll());
            assertNotNull(pp);
            st.rejectMessage(0, 0, PART);
            Request again = pp.getRequest();
            assertNotNull(again);
            // the rejected block, not the one still in flight with the rejecter
            assertEquals(0, again.off);
        } finally {
            pc.halt();
        }
    }

    /**
     * Rejecting the only block a peer holds of a piece still hands the piece back to the
     * coordinator, as it always did.
     *
     * @throws Throwable if the torrent fixture or the coordinator setup fails
     */
    @Test
    public void testRejectingTheOnlyBlockHandsThePieceBack() throws Throwable {
        PeerCoordinator pc = coordinator(newUtil());
        try {
            Peer one = peer(1);
            PeerState st = peerState(pc, one);
            st.bitfield = hasAll();
            st.choked = false;
            st.interesting = true;
            st.addRequest();
            st.rejectMessage(0, 0, PART);
            assertEquals(0, pc.livePartialCount());
            assertEquals(1, pc.orphanPartialCount());
        } finally {
            pc.halt();
        }
    }

    /**
     * A sweep that parks a partial removes it from the map it is walking, so more than one
     * idle partial has to survive the pass - with one entry the concurrent modification is
     * never reached, and the pass died on the second piece.
     *
     * @throws Throwable if the torrent fixture or the coordinator setup fails
     */
    @Test
    public void testTheSweepReQueuesEveryIdlePartial() throws Throwable {
        PeerCoordinator pc = coordinator(newUtil(), 6);
        try {
            Peer[] peers = new Peer[6];
            for (int i = 0; i < peers.length; i++) {
                peers[i] = peer(i + 1, 6);
                assertNotNull(pc.getPartialPiece(peers[i], hasAll(6)).getRequest());
            }
            assertEquals(6, pc.livePartialCount());
            // the peers are gone without their blocks being handed back, so nobody is
            // registered for these pieces any more. Which peer got which piece is the
            // selector's choice, so clear the set rather than unmarking one peer.
            for (int i = 0; i < peers.length; i++) {
                pc.wantedPiece(i).clear();
            }
            pc.checkStalledPieces(System.currentTimeMillis()
                                  + PeerCoordinator.PIECE_STALL_TIMEOUT + 60_000L);
            assertEquals(0, pc.livePartialCount());
            // six came to rest, the list holds at most four for this torrent
            assertEquals(4, pc.orphanPartialCount());
        } finally {
            pc.halt();
        }
    }

    /**
     * A parked copy is replaced by a later copy of the same piece that holds more of it, so
     * the bytes already downloaded are never lost to a smaller one.
     *
     * @throws Throwable if the torrent fixture or the coordinator setup fails
     */
    @Test
    public void testABiggerParkedCopyReplacesTheSmaller() throws Throwable {
        PeerCoordinator pc = coordinator(newUtil());
        try {
            Peer one = peer(1);
            saveCopy(pc, one, PART);
            assertEquals(PART, pc.orphanBytes(0));
            saveCopy(pc, one, 2 * PART);
            assertEquals(1, pc.orphanPartialCount());
            assertEquals(2 * PART, pc.orphanBytes(0));
        } finally {
            pc.halt();
        }
    }

    /**
     * A copy that holds no more than the one already parked is thrown away, and released -
     * its temp file goes with it, or a torrent that keeps handing back the same piece grows
     * the heap.
     *
     * @throws Throwable if the torrent fixture or the coordinator setup fails
     */
    @Test
    public void testASmallerParkedCopyIsDiscardedAndReleased() throws Throwable {
        PeerCoordinator pc = coordinator(newUtil());
        try {
            Peer one = peer(1);
            saveDiskCopy(pc, one, 2 * PART);
            assertEquals(2 * PART, pc.orphanBytes(0));
            PartialPiece smaller = saveDiskCopy(pc, one, PART);
            assertEquals(1, pc.orphanPartialCount());
            assertEquals(2 * PART, pc.orphanBytes(0));
            try {
                smaller.getHash();
                fail("the discarded copy should have been released");
            } catch (IOException e) {
                // expected
            }
        } finally {
            pc.halt();
        }
    }

    /**
     * The end game is entered when a pick cannot find a peer a piece nobody else is pulling, and
     * left again as soon as one can - not counted from the pieces left, which kept a 33-piece
     * torrent out of the end game with every piece in flight, and put a 5,000-piece torrent in
     * it whenever 32 pieces happened to be left.
     *
     * @throws Throwable if the torrent fixture or the coordinator setup fails
     */
    @Test
    public void testTheEndGameFollowsThePickNotThePieceCount() throws Throwable {
        // one piece more than the old threshold, so the count said this could not be the end
        // game no matter what the peers had run out of
        int pieces = PeerCoordinator.END_GAME_THRESHOLD + 1;
        PeerCoordinator pc = coordinator(newUtil(), pieces);
        try {
            assertFalse(pc.inEndGame());
            BitField all = new BitField(pieces);
            for (int i = 0; i < pieces; i++) {
                all.set(i);
            }
            Peer puller = peer(1, pieces);
            for (int i = 0; i < pieces; i++) {
                assertNotNull("piece " + i + " was never handed out",
                              pc.getPartialPiece(puller, all));
            }
            // every piece is in flight now, so the only way to keep a second peer working is a
            // duplicate request for a piece the first is already pulling
            Peer idle = peer(2, pieces);
            assertNotNull("the second peer was turned away instead of sharing a piece",
                          pc.getPartialPiece(idle, all));
            assertTrue(pc.inEndGame());
        } finally {
            pc.halt();
        }
    }

    /**
     * A peer is only worth keeping unchoked in the end game if its bitfield covers a piece
     * we still want - not if we have no state for it, no bitfield yet, or it holds only
     * what we already have.
     *
     * @throws Throwable if the torrent fixture or the coordinator setup fails
     */
    @Test
    public void testAPeerIsOnlySparedIfItHoldsWhatWeWant() throws Throwable {
        PeerCoordinator pc = coordinator(newUtil());
        try {
            // no PeerState yet - the handshake has not finished
            assertFalse(pc.hasSomethingWeNeed(peer(1)));
            Peer one = peer(1);
            PeerState st = peerState(pc, one);
            // bitfield still null, the have-all message has not arrived
            assertFalse(pc.hasSomethingWeNeed(one));
            // it has nothing we are missing
            st.bitfield = new BitField(1);
            assertFalse(pc.hasSomethingWeNeed(one));
            // it has the piece we want
            st.bitfield = hasAll();
            assertTrue(pc.hasSomethingWeNeed(one));
            // and the piece is disabled, so the priority-sorted scan stops before it
            pc.wantedPiece(0).setDisabled();
            assertFalse(pc.hasSomethingWeNeed(one));
        } finally {
            pc.halt();
        }
    }

    /**
     * Throwing away a piece that failed its hash check cancels the requests the peers still
     * hold for it. That only happens while the live copy is still registered, so the piece
     * is discarded before it is unmarked - parking first would skip the cancel and leave
     * the peers sending blocks for a piece that no longer exists.
     *
     * @throws Throwable if the torrent fixture or the coordinator setup fails
     */
    @Test
    public void testDiscardingAPieceCancelsThePeersRequests() throws Throwable {
        PeerCoordinator pc = coordinator(newUtil());
        try {
            Peer one = peer(1);
            pc.connected(one);
            PeerState st = peerState(pc, one);
            st.bitfield = hasAll();
            st.choked = false;
            st.interesting = true;
            st.addRequest();
            assertEquals(1, st.outstandingFor(0));
            pc.removePartialPiece(0);
            assertEquals(0, st.outstandingFor(0));
            assertEquals(0, pc.livePartialCount());
            // the copy was thrown away, not parked for another peer to pick up
            assertEquals(0, pc.orphanPartialCount());
        } finally {
            pc.halt();
        }
    }

    /**
     * A restart wipes every piece's requester set while a partial may still be live, so the
     * next sweep re-queues it rather than leaving it registered forever.
     *
     * @throws Throwable if the torrent fixture or the coordinator setup fails
     */
    @Test
    public void testARestartLeavesTheSweepToReQueueALivePartial() throws Throwable {
        PeerCoordinator pc = coordinator(newUtil());
        try {
            Peer one = peer(1);
            assertNotNull(pc.getPartialPiece(one, hasAll()).getRequest());
            pc.restart();
            assertEquals(1, pc.livePartialCount());
            pc.checkStalledPieces(System.currentTimeMillis()
                                  + PeerCoordinator.PIECE_STALL_TIMEOUT + 60_000L);
            assertEquals(0, pc.livePartialCount());
            assertEquals(1, pc.orphanPartialCount());
        } finally {
            pc.halt();
        }
    }

    /**
     * A 512 KiB piece is 32 blocks at the default part size, so a ninth peer is still
     * welcome and the tenth is refused. Against the old constant cap of eight the ninth
     * request was refused, which left 24 blocks' worth of willing peers unused on every
     * end-game piece of a large-piece torrent.
     *
     * @throws Throwable if the torrent fixture or the coordinator setup fails
     */
    @Test
    public void testABigPieceIsSharedWithMoreThanEightPeers() throws Throwable {
        int big = 512 * 1024;
        PeerCoordinator pc = coordinator(newUtil(), 1, big);
        try {
            for (int i = 1; i <= 9; i++) {
                PartialPiece pp = pc.getPartialPiece(peer(i, 1), 0);
                assertNotNull("peer " + i + " was refused", pp.getRequest());
            }
            assertEquals(9, pc.wantedPiece(0).getRequestCount());
            // the ceiling is 16, so the piece is not shared out without limit
            for (int i = 10; i <= 16; i++) {
                assertNotNull("peer " + i + " was refused",
                              pc.getPartialPiece(peer(i, 1), 0).getRequest());
            }
            assertNull(pc.getPartialPiece(peer(17, 1), 0));
            assertEquals(16, pc.wantedPiece(0).getRequestCount());
        } finally {
            pc.halt();
        }
    }

    /**
     * Hands the coordinator a memory-backed partial copy of piece 0 holding bytes of data.
     *
     * @param pc the coordinator that owns the piece records
     * @param one the peer the copy came from
     * @param bytes how much of the piece the copy holds
     * @return the copy, as handed over
     * @throws IOException on a read failure
     */
    private static PartialPiece saveCopy(PeerCoordinator pc, Peer one, int bytes)
            throws IOException {
        PartialPiece pp = new PartialPiece(new Piece(0), PIECE_LENGTH,
                                           new File(System.getProperty("java.io.tmpdir")));
        pp.read(new DataInputStream(new ByteArrayInputStream(new byte[bytes])), 0, bytes,
                new NullBWL());
        pc.savePartialPieces(one, Collections.singletonList(new Request(pp, 0, bytes)));
        return pp;
    }

    /**
     * The same, for a piece too big to keep in memory, so its temp file can be watched.
     *
     * @param pc the coordinator that owns the piece records
     * @param one the peer the copy came from
     * @param bytes how much of the piece the copy holds
     * @return the copy, as handed over
     * @throws IOException on a read failure
     */
    private static PartialPiece saveDiskCopy(PeerCoordinator pc, Peer one, int bytes)
            throws IOException {
        PartialPiece pp = new PartialPiece(new Piece(0), DISK_PIECE,
                                           new File(System.getProperty("java.io.tmpdir")));
        pp.read(new DataInputStream(new ByteArrayInputStream(new byte[bytes])), 0, bytes,
                new NullBWL());
        pc.savePartialPieces(one, Collections.singletonList(new Request(pp, 0, bytes)));
        return pp;
    }
}
