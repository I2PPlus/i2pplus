package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import net.i2p.I2PAppContext;

/**
 * Pins who gets a free upload slot. The unchoke used to walk the peer list in order and
 * prefer only the peer that has us unchoked, so below the slot cap upload policy was
 * first-come-first-served: no step anywhere ranked peers by what they were actually
 * getting out of us. Slots are now ranked by the rate we have been sending each peer, with
 * a standing reservation for peers we are not serving at all, so a swarm cannot close over
 * itself.
 *
 * @since 0.9.72+
 */
public class UnchokeRankingTest {

    /** Piece length in bytes for the fixture torrent. */
    private static final int PIECE_LENGTH = 16 * 1024;

    /** Pieces in the fixture torrent. */
    private static final int PIECES = 64;

    /** Seconds per checker pass, from {@code PeerCoordinator.CHECK_PERIOD}. */
    private static final long PASS_SECONDS = PeerCoordinator.CHECK_PERIOD / 1000;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public UnchokeRankingTest() {}

    /**
     * A bencoded single-file torrent with the given number of pieces.
     *
     * @param pieces how many pieces the torrent has
     * @return the bencoded torrent bytes
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

    /** The fixture metainfo, rebuilt per call so each test owns its own copy. */
    private static MetaInfo metainfo() throws Throwable {
        return new MetaInfo(new ByteArrayInputStream(torrentBytes(PIECES)));
    }

    /**
     * A coordinator over a torrent with nothing on disk and no coordinator listener. The
     * caller must halt it to stop the checker timer.
     *
     * @param mi the metainfo to build the storage from
     * @return a coordinator with no uploaders yet
     * @throws Throwable on metainfo or storage failure
     */
    private static PeerCoordinator coordinator(MetaInfo mi) throws Throwable {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        Storage st = new Storage(util, new File("/tmp/snark-rank-test"), mi, null, false);
        return new PeerCoordinator(
                util, new byte[20], mi.getInfoHash(), mi, st, null, null, new NullBWL());
    }

    /**
     * A bandwidth listener that reports no limit, so nothing narrows the slot count.
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
     * Bytes to record for one checker pass to put a peer at the given upload rate from us.
     *
     * @param bps the rate to record, in bytes per second
     * @return the byte count for one pass that yields that rate
     */
    private static int bytesForRate(long bps) {
        // getRate() weights one sample by RATE_DEPTH against a factor summing to the same,
        // so a single pass sample of n bytes reads back as n / PASS_SECONDS
        return (int) (bps * PASS_SECONDS);
    }

    /**
     * A peer that is choking us and wants pieces from us, so a candidate for a free upload
     * slot, with an upload rate from us already sampled.
     *
     * @param mi the torrent the peer is on
     * @param n the peer number, used as its id so peers sort into a known order
     * @param rateBps the upload rate from us to record for it, in bytes per second
     * @param unchokedUs true if this peer has us unchoked, so serving it is tit-for-tat
     * @return a socketless peer that is choking us and wants pieces from us
     * @throws Throwable on peer id failure
     */
    private static Peer candidate(MetaInfo mi, int n, long rateBps, boolean unchokedUs)
            throws Throwable {
        byte[] id = new byte[32];
        id[0] = (byte) n;
        PeerID pID = new PeerID(id, (I2PSnarkUtil) null);
        Peer peer = new Peer(pID, new byte[20], mi.getInfoHash(), mi);
        PeerConnectionOut out =
                new PeerConnectionOut(peer, new DataOutputStream(new ByteArrayOutputStream()));
        PeerState state = new PeerState(peer, null, new NullBWL(), mi, null, out);
        state.interested = true;
        state.interesting = unchokedUs;
        state.choked = !unchokedUs;
        peer.state = state;
        if (rateBps > 0) {
            peer.uploaded(bytesForRate(rateBps));
            peer.setRateHistory();
        }
        return peer;
    }

    /**
     * A peer we are already unchoking, at the given upload rate, so it counts against the
     * slots held for peers we are serving.
     *
     * @param mi the torrent the peer is on
     * @param n the peer number
     * @param rateBps the upload rate from us to record for it, in bytes per second
     * @return a socketless peer we have unchoked
     * @throws Throwable on peer id failure
     */
    private static Peer uploader(MetaInfo mi, int n, long rateBps) throws Throwable {
        Peer peer = candidate(mi, n, rateBps, false);
        peer.state.choking = false;
        return peer;
    }

    /**
     * The rate floor is a real threshold, not a rounding artefact: one byte per second
     * below it is not served, at it is.
     */
    @Test
    public void testRateFloorBoundary() {
        assertFalse(PeerCoordinator.isBeingServed(PeerCoordinator.UPLOAD_RATE_FLOOR_BPS - 1));
        assertTrue(PeerCoordinator.isBeingServed(PeerCoordinator.UPLOAD_RATE_FLOOR_BPS));
        assertTrue(PeerCoordinator.isBeingServed(1024 * 1024));
    }

    /**
     * The reservation never vanishes for a small slot count and never swallows the pool for
     * a large one.
     */
    @Test
    public void testReservationSize() {
        assertEquals(3, PeerCoordinator.optimisticSlots(PeerCoordinator.MAX_UPLOADERS));
        assertEquals(1, PeerCoordinator.optimisticSlots(4));
        assertEquals(1, PeerCoordinator.optimisticSlots(0));
        assertTrue(PeerCoordinator.optimisticSlots(PeerCoordinator.MAX_UPLOADERS)
                   < PeerCoordinator.MAX_UPLOADERS / 2);
    }

    /**
     * The reservation only takes over once the peers we are serving hold more than all but
     * the reserved slots.
     */
    @Test
    public void testReservationThreshold() {
        int cap = PeerCoordinator.MAX_UPLOADERS;
        int reserve = PeerCoordinator.optimisticSlots(cap);
        assertFalse(PeerCoordinator.slotForUnservedPeers(cap - reserve, cap, reserve));
        assertTrue(PeerCoordinator.slotForUnservedPeers(cap - reserve + 1, cap, reserve));
        assertFalse(PeerCoordinator.slotForUnservedPeers(0, cap, reserve));
    }

    /**
     * The ranking puts a peer that has us unchoked ahead of one that has us choked, and
     * within that ranks by the rate we have been sending, fastest first.
     *
     * @throws Throwable on fixture construction failure
     */
    @Test
    public void testRankingIsReciprocityThenRate() throws Throwable {
        MetaInfo mi = metainfo();
        Peer generousSlow = candidate(mi, 1, 400, true);
        Peer greedyFast = candidate(mi, 2, 4000, false);
        Peer generousFast = candidate(mi, 3, 4000, true);
        assertTrue(PeerCoordinator.compareUnchokeCandidates(generousSlow, greedyFast) < 0);
        assertTrue(PeerCoordinator.compareUnchokeCandidates(greedyFast, generousSlow) > 0);
        assertTrue(PeerCoordinator.compareUnchokeCandidates(generousFast, generousSlow) < 0);
        assertEquals(0, PeerCoordinator.compareUnchokeCandidates(generousSlow, generousSlow));
    }

    /**
     * The ranking is antisymmetric, so a sort of the candidate list cannot order a pair both
     * ways.
     *
     * @throws Throwable on fixture construction failure
     */
    @Test
    public void testRankingIsAntisymmetric() throws Throwable {
        MetaInfo mi = metainfo();
        List<Peer> peers = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            peers.add(candidate(mi, i, i * 500L, i % 2 == 0));
        }
        for (Peer a : peers) {
            for (Peer b : peers) {
                assertTrue(Integer.signum(PeerCoordinator.compareUnchokeCandidates(a, b))
                           == -Integer.signum(PeerCoordinator.compareUnchokeCandidates(b, a)));
            }
        }
    }

    /**
     * The faster-served candidate takes the slot, which is the ranking the unchoke never
     * had: it used to take whoever stood at the head of the peer list.
     *
     * @throws Throwable on fixture construction failure
     */
    @Test
    public void testFasterServedPeerGetsTheSlot() throws Throwable {
        MetaInfo mi = metainfo();
        PeerCoordinator pc = coordinator(mi);
        try {
            Peer slow = candidate(mi, 1, 400, false);
            Peer fast = candidate(mi, 2, 4000, false);
            pc.peers.add(slow);
            pc.peers.add(fast);
            synchronized (pc.peers) {
                pc.unchokePeer(Collections.<Peer>emptySet());
            }
            assertFalse(fast.isChoking());
            assertTrue(slow.isChoking());
        } finally {
            pc.halt();
        }
    }

    /**
     * A peer that has us unchoked outranks a faster peer that has us choked, so the ranking
     * stays tit-for-tat rather than becoming a pure auction.
     *
     * @throws Throwable on fixture construction failure
     */
    @Test
    public void testReciprocityOutranksRate() throws Throwable {
        MetaInfo mi = metainfo();
        PeerCoordinator pc = coordinator(mi);
        try {
            Peer generous = candidate(mi, 1, 400, true);
            Peer greedy = candidate(mi, 2, 4000, false);
            pc.peers.add(greedy);
            pc.peers.add(generous);
            synchronized (pc.peers) {
                pc.unchokePeer(Collections.<Peer>emptySet());
            }
            assertFalse(generous.isChoking());
            assertTrue(greedy.isChoking());
        } finally {
            pc.halt();
        }
    }

    /**
     * Once the peers we are serving hold more than all but the reserved slots, the next slot
     * goes to a peer we are not serving at all, which is how a peer that joined late ever
     * gets its first piece.
     *
     * @throws Throwable on fixture construction failure
     */
    @Test
    public void testUnservedPeerGetsTheReservedSlot() throws Throwable {
        MetaInfo mi = metainfo();
        PeerCoordinator pc = coordinator(mi);
        try {
            int cap = PeerCoordinator.MAX_UPLOADERS;
            int reserve = PeerCoordinator.optimisticSlots(cap);
            // one past the point where the reservation takes effect
            int serving = cap - reserve + 1;
            for (int i = 1; i <= serving; i++) {
                pc.peers.add(uploader(mi, i, 4000));
            }
            pc.setUploaders(serving, serving);
            Peer newcomer = candidate(mi, 100, 0, false);
            Peer recentlyServed = candidate(mi, 101, 4000, false);
            pc.peers.add(recentlyServed);
            pc.peers.add(newcomer);
            assertEquals(serving, pc.getUploaders());
            assertTrue(PeerCoordinator.slotForUnservedPeers(serving, cap, reserve));
            synchronized (pc.peers) {
                pc.unchokePeer(Collections.<Peer>emptySet());
            }
            assertFalse(newcomer.isChoking());
            assertTrue(recentlyServed.isChoking());
        } finally {
            pc.halt();
        }
    }

    /**
     * While the peers we are serving hold few slots, a peer we have served recently outranks
     * a newcomer, so the reservation does not turn into a lottery that ignores service.
     *
     * @throws Throwable on fixture construction failure
     */
    @Test
    public void testServedPeerWinsWhileSlotsAreFree() throws Throwable {
        MetaInfo mi = metainfo();
        PeerCoordinator pc = coordinator(mi);
        try {
            Peer newcomer = candidate(mi, 100, 0, false);
            Peer recentlyServed = candidate(mi, 101, 4000, false);
            pc.peers.add(newcomer);
            pc.peers.add(recentlyServed);
            synchronized (pc.peers) {
                pc.unchokePeer(Collections.<Peer>emptySet());
            }
            assertFalse(recentlyServed.isChoking());
            assertTrue(newcomer.isChoking());
        } finally {
            pc.halt();
        }
    }
}
