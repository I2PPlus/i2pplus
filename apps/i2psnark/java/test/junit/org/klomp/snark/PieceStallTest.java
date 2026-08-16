package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;

import net.i2p.I2PAppContext;

import org.junit.Test;

/**
 * Tests for piece stall detection: a requested piece that receives no data for
 * PIECE_STALL_TIMEOUT is re-queued so it can be requested from other peers.
 *
 * @since 0.9.71+
 */
public class PieceStallTest {

    private static final long TIMEOUT = 5 * 60 * 1000L;

    private static PeerID newPeerID(I2PSnarkUtil util) throws Exception {
        return new PeerID(new byte[32], util);
    }

    /** A freshly created piece records an initial activity time. */
    @Test
    public void testLastActiveInitial() {
        Piece p = new Piece(0);
        long now = System.currentTimeMillis();
        assertTrue(p.getLastActive() <= now);
        assertTrue(p.getLastActive() >= now - 1000);
    }

    /** setActive() advances the activity time. */
    @Test
    public void testSetActiveAdvances() throws Exception {
        Piece p = new Piece(0);
        long before = p.getLastActive();
        Thread.sleep(5);
        p.setActive();
        assertTrue(p.getLastActive() > before);
    }

    /** Marking a piece requested stamps the activity time. */
    @Test
    public void testRequestStampsActivity() throws Exception {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        Piece p = new Piece(0);
        long now = System.currentTimeMillis();
        p.setRequested(newPeerID(util), true);
        assertTrue(p.isRequested());
        assertTrue(p.getLastActive() >= now - 1000);
    }

    /** An unrequested piece is never stalled, even after the timeout. */
    @Test
    public void testUnrequestedNotStalled() throws Exception {
        Piece p = new Piece(0);
        long now = System.currentTimeMillis();
        assertFalse(PeerCoordinator.isStalled(p, now, TIMEOUT));
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        p.setRequested(newPeerID(util), true);
        assertFalse(PeerCoordinator.isStalled(p, now, TIMEOUT));
    }

    /** A requested piece that received no data past the timeout is stalled. */
    @Test
    public void testStaleRequestIsStalled() throws Exception {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        Piece p = new Piece(0);
        long now = System.currentTimeMillis();
        p.setRequested(newPeerID(util), true);
        p._lastActive = now - TIMEOUT - 1000;
        assertTrue(PeerCoordinator.isStalled(p, now, TIMEOUT));
    }

    /** Receiving a chunk stamps the piece activity. */
    @Test
    public void testChunkReadStampsActivity() throws Exception {
        Piece piece = new Piece(0);
        PartialPiece pp = new PartialPiece(piece, 32768, null);
        long before = piece.getLastActive();
        byte[] data = new byte[16384];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        Thread.sleep(5);
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(data));
        pp.read(din, 0, data.length, new StubBWL());
        assertTrue(piece.getLastActive() > before);
        assertTrue(pp.hasSubBlock(0));
    }

    /** Minimal BandwidthListener for the chunk read test. */
    private static class StubBWL implements BandwidthListener {

        @Override
        public long getUploadRate() {
            return 0;
        }

        @Override
        public long getDownloadRate() {
            return 0;
        }

        @Override
        public void uploaded(int size) {}

        @Override
        public void downloaded(int size) {}

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
            return -1;
        }

        @Override
        public long getDownBWLimit() {
            return -1;
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
