package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PClientFactory;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import org.junit.Test;
import org.klomp.snark.bencode.InvalidBEncodingException;

/**
 * Pins which swarm peers a torrent keeps remembering. The tracker, the DHT and PEX go on
 * naming a peer that has gone away for the life of the torrent, and the peer list the
 * torrent view counts was built from those names alone, so a swarm that had already left
 * kept reading as a full one for hours. A peer now goes when the router stops being able to
 * resolve its destination - four failed network-database searches, then a five minute hold -
 * or when no source has named it again inside the retention window. If the swarm names it
 * again after that hold, it is remembered afresh.
 *
 * @since 0.9.72+
 */
public class SwarmPeerRetentionTest {

    /** The most a round's jitter adds to an announce interval, as the loop draws it. */
    private static final int MAX_JITTER_MS = 120 * 1000 - 1;

    /** Piece length in bytes for the fixture torrent. */
    private static final int PIECE_LENGTH = 16 * 1024;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     */
    public SwarmPeerRetentionTest() {}

    /**
     * A peer the router is connected to is kept whatever the destination backoff and its own
     * age say - the connection is better evidence than either.
     */
    @Test
    public void testAConnectedPeerIsAlwaysKept() {
        assertTrue(PeerCoordinator.keepsSwarmPeer(5 * 60 * 1000L, 0L, 1_000_000L, true));
        assertTrue(PeerCoordinator.keepsSwarmPeer(0L, 0L, 1_000_000L, true));
    }

    /**
     * A peer the router cannot resolve is dropped as soon as it is held that way, even one
     * the swarm named this second. That is the case the old rule missed: the tracker keeps
     * advertising a dead peer, so its last-seen time stays fresh while its destination is
     * gone.
     */
    @Test
    public void testAPeerWithAnUnresolvableDestinationIsDropped() {
        long now = 1_000_000L;
        assertFalse(PeerCoordinator.keepsSwarmPeer(1L, now, now - 60 * 60 * 1000L, false));
        assertFalse(PeerCoordinator.keepsSwarmPeer(I2PSnarkUtil.DEST_CACHE_NEGATIVE_MS, now,
                                                  now - 60 * 60 * 1000L, false));
    }

    /**
     * A peer no source has named since the cutoff goes, and one named at the cutoff stays -
     * the boundary the retention window turns on.
     */
    @Test
    public void testAPeerThatHasNotResurfacedIsDropped() {
        long now = 1_000_000L;
        long cutoff = now - PeerCoordinator.SWARM_PEER_TTL;
        assertFalse(PeerCoordinator.keepsSwarmPeer(0L, cutoff - 1, cutoff, false));
        assertTrue(PeerCoordinator.keepsSwarmPeer(0L, cutoff, cutoff, false));
        assertTrue(PeerCoordinator.keepsSwarmPeer(0L, now, cutoff, false));
    }

    /**
     * The window outlives two announce rounds at the slowest cadence the router runs - a
     * seeding torrent's, three times the base interval plus its jitter - so a live peer is
     * never dropped for absence alone, and it stays far below the two hours that kept a
     * departed swarm counted for seven rounds on end.
     */
    @Test
    public void testTheWindowCoversASlowRoundButNotTheOldTwoHours() {
        long slowestRound = 3 * TrackerClient.SLEEP * 60_000L + MAX_JITTER_MS;
        assertTrue("a live peer must survive two rounds at the slowest cadence",
                   PeerCoordinator.SWARM_PEER_TTL > 2 * slowestRound);
        assertTrue("the window must stay well under the old two hours",
                   PeerCoordinator.SWARM_PEER_TTL < 2 * 60 * 60 * 1000L);
    }

    /**
     * The destination backoff bites long before the window does, which is what makes the
     * drop happen while the swarm is still naming the peer rather than after it stops.
     */
    @Test
    public void testTheDestinationBackoffBitesBeforeTheWindow() {
        assertTrue(I2PSnarkUtil.DEST_CACHE_NEGATIVE_MS < PeerCoordinator.SWARM_PEER_TTL);
        long ladder = 0;
        for (int i = 0; i < I2PSnarkUtil.DEST_RETRY_ATTEMPTS; i++) {
            ladder += I2PSnarkUtil.destCacheTtl(null, i);
        }
        assertTrue("a peer given up on must stop being counted inside the window",
                   ladder < PeerCoordinator.SWARM_PEER_TTL);
    }

    /**
     * A peer is counted while it is dialable, stops being counted the moment its destination
     * is held as unresolvable even though the swarm just named it, and is counted again once
     * that hold has passed and the swarm names it once more.
     *
     * @throws Throwable on metainfo, storage or peer id failure
     */
    @Test
    public void testAnUnresolvablePeerStopsBeingCountedAndReturns() throws Throwable {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        PeerCoordinator pc = coordinator(util);
        try {
            byte[] raw = new byte[Hash.HASH_LENGTH];
            raw[3] = 9;
            Peer peer = peer(util, raw);
            pc.recordSwarmPeer(peer);
            assertEquals(1, pc.getSwarmPeerCount());

            // four searches failed and the answer is now held: the count drops without the
            // swarm being asked, and naming the peer again while held buys it nothing
            util.noteDest(new Hash(raw), null);
            assertEquals("a peer with no destination is not counted", 0, pc.getSwarmPeerCount());
            pc.recordSwarmPeer(peer);
            assertEquals(0, pc.getSwarmPeerCount());

            // the hold has passed and the tracker names the peer again: it is remembered
            util.cacheDestination(new Hash(raw), null,
                                  System.currentTimeMillis() - 20 * 60_000L, 0);
            pc.recordSwarmPeer(peer);
            assertEquals("a peer we can ask about again is counted", 1, pc.getSwarmPeerCount());
        } finally {
            pc.halt();
        }
    }

    /**
     * A peer whose destination resolved is still counted while the swarm keeps naming it, so
     * the drop is about a destination that cannot be found, not about every cached answer.
     *
     * @throws Throwable on metainfo, storage, peer id or key generation failure
     */
    @Test
    public void testAResolvedPeerKeepsBeingCounted() throws Throwable {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        PeerCoordinator pc = coordinator(util);
        try {
            Destination dest = destination();
            Peer peer = peer(util, dest.calculateHash().getData());
            pc.recordSwarmPeer(peer);
            util.noteDest(dest.calculateHash(), dest);
            pc.recordSwarmPeer(peer);
            assertEquals(1, pc.getSwarmPeerCount());
        } finally {
            pc.halt();
        }
    }

    /**
     * A fresh destination, for a peer whose lookup did resolve.
     *
     * @return a destination with newly generated keys
     * @throws Exception if the keys cannot be generated
     */
    private static Destination destination() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        I2PClientFactory.createClient().createDestination(out);
        return Destination.create(new ByteArrayInputStream(out.toByteArray()));
    }

    /**
     * A hash-only peer for the fixture torrent.
     *
     * @param util the util that supplies the shared destination answers
     * @param destHash the peer's destination hash, 32 bytes
     * @return an unconnected peer carrying that id
     * @throws InvalidBEncodingException if the hash is not 32 bytes
     */
    private static Peer peer(I2PSnarkUtil util, byte[] destHash)
            throws InvalidBEncodingException {
        return new Peer(new PeerID(destHash, util), new byte[20], new byte[20], null);
    }

    /**
     * A coordinator over a torrent with nothing on disk, whose checker timer is stopped by
     * the caller's {@code halt()}.
     *
     * @param util the util the coordinator and the peer ids share
     * @return a coordinator for a small fixture torrent
     * @throws Throwable on metainfo or storage failure
     */
    private static PeerCoordinator coordinator(I2PSnarkUtil util) throws Throwable {
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(torrentBytes(8)));
        Storage st =
                new Storage(util, new File("/tmp/opencode/snark-swarm-test"), mi, null, false);
        return new PeerCoordinator(
                util, new byte[20], mi.getInfoHash(), mi, st, null, null, new NullBWL());
    }

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

    /**
     * A bandwidth listener that reports no limit, so the connection cap is set by the
     * torrent's piece count alone.
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
}
