package org.klomp.snark;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.RandomAccess;

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

    /** Size of one requested block, the unit the queue is built in. */
    private static final int CHUNK = 16384;

    /** Piece length the three-chunk queue fits in, in bytes. */
    private static final int PLEN = 3 * CHUNK;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public GetOutstandingRequestTest() {}

    /**
     * A partial piece of the default three-chunk length.
     *
     * @param piece the piece number
     * @return a partial piece that three whole chunks fit in
     */
    private static PartialPiece partial(int piece) {
        return new PartialPiece(new Piece(piece), PLEN, null);
    }

    /**
     * A partial piece long enough to hold {@code chunks} whole chunks, for a queue deeper
     * than the three-chunk default.
     *
     * @param piece the piece number
     * @param chunks how many chunks the piece is long enough for
     * @return a partial piece of that piece
     */
    private static PartialPiece partial(int piece, int chunks) {
        return new PartialPiece(new Piece(piece), chunks * CHUNK, null);
    }

    /**
     * PeerState with the private request queue populated via reflection.
     *
     * @param requests the requests to install into the private queue
     * @return a choked, unconnected peer state whose queue holds the given requests
     * @throws Throwable if the torrent bytes cannot be parsed or the private field is inaccessible
     */
    private static PeerState stateWith(List<Request> requests) throws Throwable {
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

    /**
     * Whole-chunk requests for one piece, in the order they would have been sent.
     *
     * @param pp the piece they belong to
     * @param chunks how many requests to build
     * @return the requests, chunk 0 first
     */
    private static List<Request> requests(PartialPiece pp, int chunks) {
        List<Request> rv = new ArrayList<>(chunks);
        for (int i = 0; i < chunks; i++) {
            rv.add(new Request(pp, i * CHUNK, CHUNK));
        }
        return rv;
    }

    /**
     * A chunk that arrives in order matches the head of the queue and consumes exactly it.     *
     * @throws Throwable if the torrent bytes cannot be parsed or the private field is inaccessible
     */

    @Test
    public void inOrderChunkMatchesHead() throws Throwable {
        PeerState ps = stateWith(requests(partial(0), 3));
        Request req = ps.getOutstandingRequest(0, 0, CHUNK);
        assertNotNull(req);
        assertEquals(0, req.off);
        // head consumed; the other two remain in order
        assertEquals(2, countRequests(ps));
    }

    /**
     * A chunk that arrives before its predecessors is matched, not dropped, and the requests
     * stepped over it are requeued rather than lost.     *
     * @throws Throwable if the torrent bytes cannot be parsed or the private field is inaccessible
     */

    @Test
    public void outOfOrderMiddleChunkIsMatchedNotDropped() throws Throwable {
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

    /**
     * The requests stepped over go back at the tail, in the order they were sent, so a
     * non-fast peer is still asked for them and none is dropped.     *
     * @throws Throwable if the torrent bytes cannot be parsed or the private field is inaccessible
     */

    @Test
    public void skippedRequestsAreRequeuedAtTail() throws Throwable {
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

    /**
     * A matching chunk is still found behind a request for a different piece.     *
     * @throws Throwable if the torrent bytes cannot be parsed or the private field is inaccessible
     */

    @Test
    public void chunkOfDifferentPieceBetweenOthersStillMatched() throws Throwable {
        // regression: the old scan stopped advancing when the piece number
        // changed, dropping a perfectly good matching chunk behind it
        PeerState ps = stateWith(java.util.Arrays.asList(
                new Request(partial(1), 0, CHUNK),
                new Request(partial(0), CHUNK, CHUNK)));
        Request req = ps.getOutstandingRequest(0, CHUNK, CHUNK);
        assertNotNull("matching chunk behind an unrelated request must be found", req);
        assertEquals(CHUNK, req.off);
    }

    /**
     * A chunk for a piece we never asked for matches nothing.     *
     * @throws Throwable if the torrent bytes cannot be parsed or the private field is inaccessible
     */

    @Test
    public void unknownPieceReturnsNull() throws Throwable {
        PeerState ps = stateWith(requests(partial(0), 1));
        assertNull(ps.getOutstandingRequest(5, 0, CHUNK));
    }

    /**
     * A chunk whose length is not the one we asked for matches nothing.     *
     * @throws Throwable if the torrent bytes cannot be parsed or the private field is inaccessible
     */

    @Test
    public void wrongLengthReturnsNull() throws Throwable {
        PeerState ps = stateWith(requests(partial(0), 1));
        assertNull(ps.getOutstandingRequest(0, 0, CHUNK / 2));
    }

    /**
     * A request queue deep enough that the matching chunk is the last one scanned: the chunk
     * is matched, and the requests stepped over it come back in the order they were sent.     *
     * @throws Throwable if the torrent bytes cannot be parsed or the private field is inaccessible
     */

    @Test
    public void deepPipelineMatchesTheLastChunkAndKeepsTheRestInOrder() throws Throwable {
        // a deep pipeline is what the index walk costs: the match is at the far end, so
        // every earlier request is stepped over and then requeued
        PeerState ps = stateWith(requests(partial(0, 40), 40));
        Request req = ps.getOutstandingRequest(0, 39 * CHUNK, CHUNK);
        assertNotNull(req);
        assertEquals(39 * CHUNK, req.off);
        assertEquals(39, countRequests(ps));
        List<?> rest = requestList(ps);
        for (int i = 0; i < rest.size(); i++) {
            assertEquals(i * CHUNK, ((Request) rest.get(i)).off);
        }
    }

    /**
     * The queue supports positional access in constant time, which is what the arrival path
     * depends on.     *
     * @throws Throwable if the torrent bytes cannot be parsed or the private field is inaccessible
     */

    @Test
    public void requestQueueIsRandomAccess() throws Throwable {
        // the arrival path walks the queue by index, so a list that has to hop from the
        // head for every get(i) makes each received block quadratic in the pipeline depth
        assertTrue(requestList(ps0()) instanceof RandomAccess);
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * An empty queue, for the checks that look at the queue itself rather than its contents.
     *
     * @return a peer state with no requests outstanding
     * @throws Throwable if the torrent bytes cannot be parsed or the private field is inaccessible
     */
    private static PeerState ps0() throws Throwable {
        return stateWith(new ArrayList<Request>(0));
    }

    private static int countRequests(PeerState ps) throws Throwable {
        return requestList(ps).size();
    }

    @SuppressWarnings("unchecked")
    private static List<?> requestList(PeerState ps) throws Throwable {
        Field f = PeerState.class.getDeclaredField("outstandingRequests");
        f.setAccessible(true);
        return (List<?>) f.get(ps);
    }
}
