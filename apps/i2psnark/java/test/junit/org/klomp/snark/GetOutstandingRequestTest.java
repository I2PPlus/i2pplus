package org.klomp.snark;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Tests for out-of-order chunk handling in {@link PeerState#getOutstandingRequest}: a chunk
 * arriving before its predecessors must be matched (not dropped), preceding requests must be
 * requeued rather than lost, and the matched request must be removed exactly once.
 *
 * Runs with {@code choked = true} and no connection so no network sends occur; the requeue
 * ordering for non-fast peers is still fully exercised.
 *
 * @since 0.9.71+
 */
public class GetOutstandingRequestTest {

    private static final int CHUNK = 16384;
    private static final int PLEN = 3 * CHUNK;

    private static PartialPiece partial(int piece) {
        return new PartialPiece(new Piece(piece), PLEN, null);
    }

    /** PeerState with the private request queue populated via reflection. */
    private static PeerState stateWith(List<Request> requests) throws Exception {
        MetaInfo mi = new MetaInfo(new java.io.ByteArrayInputStream(
                PeerStateTest.buildTorrentBytes(new byte[40])));
        PeerID pid = new PeerID(new byte[32], (I2PSnarkUtil) null);
        Peer peer = new Peer(pid, new byte[20], mi.getInfoHash(), mi);
        PeerState ps = new PeerState(peer, null, null, mi, null, null);
        ps.choked = true;
        Field f = PeerState.class.getDeclaredField("outstandingRequests");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Request> list = (List<Request>) f.get(ps);
        list.addAll(requests);
        return ps;
    }

    private static List<Request> requests(PartialPiece pp, int chunks) {
        List<Request> rv = new ArrayList<>(chunks);
        for (int i = 0; i < chunks; i++) {
            rv.add(new Request(pp, i * CHUNK, CHUNK));
        }
        return rv;
    }

    @Test
    public void inOrderChunkMatchesHead() throws Exception {
        PeerState ps = stateWith(requests(partial(0), 3));
        Request req = ps.getOutstandingRequest(0, 0, CHUNK);
        assertNotNull(req);
        assertEquals(0, req.off);
        // head consumed; the other two remain in order
        assertEquals(2, countRequests(ps));
    }

    @Test
    public void outOfOrderMiddleChunkIsMatchedNotDropped() throws Exception {
        // peers may deliver chunks out of order; the middle chunk arriving
        // first must be matched and the earlier requests requeued to the
        // tail, not discarded
        PeerState ps = stateWith(requests(partial(0), 3));
        Request mid = ps.getOutstandingRequest(0, CHUNK, CHUNK);
        assertNotNull(mid);
        assertEquals(CHUNK, mid.off);
        // matched one removed, two skipped ones requeued at the tail
        assertEquals(2, countRequests(ps));
    }

    @Test
    public void skippedRequestsAreRequeuedAtTail() throws Exception {
        PartialPiece pp = partial(0);
        List<Request> reqs = requests(pp, 3);
        PeerState ps = stateWith(reqs);
        ps.getOutstandingRequest(0, CHUNK, CHUNK); // consume middle
        // remaining order must be [chunk2, chunk0]
        assertEquals(2, countRequests(ps));
        List<?> rest = requestList(ps);
        assertEquals(CHUNK * 2, ((Request) rest.get(0)).off);
        assertEquals(0, ((Request) rest.get(1)).off);
    }

    @Test
    public void chunkOfDifferentPieceBetweenOthersStillMatched() throws Exception {
        // regression: the old scan stopped advancing when the piece number
        // changed, dropping a perfectly good matching chunk behind it
        PeerState ps = stateWith(java.util.Arrays.asList(
                new Request(partial(1), 0, CHUNK),
                new Request(partial(0), CHUNK, CHUNK)));
        Request req = ps.getOutstandingRequest(0, CHUNK, CHUNK);
        assertNotNull("matching chunk behind an unrelated request must be found", req);
        assertEquals(CHUNK, req.off);
    }

    @Test
    public void unknownPieceReturnsNull() throws Exception {
        PeerState ps = stateWith(requests(partial(0), 1));
        assertNull(ps.getOutstandingRequest(5, 0, CHUNK));
    }

    @Test
    public void wrongLengthReturnsNull() throws Exception {
        PeerState ps = stateWith(requests(partial(0), 1));
        assertNull(ps.getOutstandingRequest(0, 0, CHUNK / 2));
    }

    // ---- helpers ------------------------------------------------------------

    private static int countRequests(PeerState ps) throws Exception {
        return requestList(ps).size();
    }

    @SuppressWarnings("unchecked")
    private static List<?> requestList(PeerState ps) throws Exception {
        Field f = PeerState.class.getDeclaredField("outstandingRequests");
        f.setAccessible(true);
        return (List<?>) f.get(ps);
    }
}
