package org.klomp.snark.web;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.Map;

import org.klomp.snark.Peer;
import org.klomp.snark.PeerID;
import org.junit.Test;

/**
 * Tests for I2PSnarkServlet appendPeerRow static helpers:
 * {@link I2PSnarkServlet#classifyPeerStatus},
 * {@link I2PSnarkServlet.PeerStatus}.
 *
 * @since 0.9.71+
 */
public class AppendPeerRowValidatorTest {

    @Test
    public void testClassifyPeerStatusInactive() {
        Peer peer = new MockPeer(0, 0, 100000, false, false, false, false);
        I2PSnarkServlet.PeerStatus ps = I2PSnarkServlet.classifyPeerStatus(peer);
        assertEquals("inactive", ps.status);
        assertFalse(ps.isTx);
        assertFalse(ps.isRx);
    }

    @Test
    public void testClassifyPeerStatusActiveTxOnly() {
        Peer peer = new MockPeer(1000, 0, 1000, false, false, false, false);
        I2PSnarkServlet.PeerStatus ps = I2PSnarkServlet.classifyPeerStatus(peer);
        assertEquals("active", ps.status);
        assertTrue(ps.isTx);
        assertFalse(ps.isRx);
    }

    @Test
    public void testClassifyPeerStatusActiveRxOnly() {
        Peer peer = new MockPeer(0, 1000, 1000, false, false, false, false);
        I2PSnarkServlet.PeerStatus ps = I2PSnarkServlet.classifyPeerStatus(peer);
        assertEquals("active", ps.status);
        assertFalse(ps.isTx);
        assertTrue(ps.isRx);
    }

    @Test
    public void testClassifyPeerStatusActiveBoth() {
        Peer peer = new MockPeer(1000, 1000, 1000, false, false, false, false);
        I2PSnarkServlet.PeerStatus ps = I2PSnarkServlet.classifyPeerStatus(peer);
        assertEquals("active", ps.status);
        assertTrue(ps.isTx);
        assertTrue(ps.isRx);
    }

    @Test
    public void testClassifyPeerStatusInactiveDueToTime() {
        Peer peer = new MockPeer(1000, 1000, 100000, false, false, false, false);
        I2PSnarkServlet.PeerStatus ps = I2PSnarkServlet.classifyPeerStatus(peer);
        assertEquals("inactive", ps.status);
        assertFalse(ps.isTx);
        assertFalse(ps.isRx);
    }

    @Test
    public void testClassifyPeerStatusActiveNotTxWhenInteresting() {
        Peer peer = new MockPeer(1000, 0, 1000, true, false, false, false);
        I2PSnarkServlet.PeerStatus ps = I2PSnarkServlet.classifyPeerStatus(peer);
        assertEquals("active", ps.status);
        assertFalse(ps.isTx);
        assertFalse(ps.isRx);
    }

    @Test
    public void testClassifyPeerStatusActiveNotTxWhenChoking() {
        Peer peer = new MockPeer(1000, 0, 1000, false, false, true, false);
        I2PSnarkServlet.PeerStatus ps = I2PSnarkServlet.classifyPeerStatus(peer);
        assertEquals("active", ps.status);
        assertFalse(ps.isTx);
        assertFalse(ps.isRx);
    }

    @Test
    public void testClassifyPeerStatusActiveNotRxWhenInterested() {
        Peer peer = new MockPeer(0, 1000, 1000, false, true, false, false);
        I2PSnarkServlet.PeerStatus ps = I2PSnarkServlet.classifyPeerStatus(peer);
        assertEquals("active", ps.status);
        assertFalse(ps.isTx);
        assertFalse(ps.isRx);
    }

    @Test
    public void testClassifyPeerStatusActiveNotRxWhenChoked() {
        Peer peer = new MockPeer(0, 1000, 1000, false, false, false, true);
        I2PSnarkServlet.PeerStatus ps = I2PSnarkServlet.classifyPeerStatus(peer);
        assertEquals("active", ps.status);
        assertFalse(ps.isTx);
        assertFalse(ps.isRx);
    }

    // ---- Mock Peer implementation ----

    private static class MockPeer extends Peer {
        private final long uploadRate;
        private final long downloadRate;
        private final long inactiveTime;
        private final boolean interesting;
        private final boolean interested;
        private final boolean choking;
        private final boolean choked;

        MockPeer(long uploadRate, long downloadRate, long inactiveTime,
                 boolean interesting, boolean interested, boolean choking, boolean choked) {
            super(null, new byte[0], new byte[0], null);
            this.uploadRate = uploadRate;
            this.downloadRate = downloadRate;
            this.inactiveTime = inactiveTime;
            this.interesting = interesting;
            this.interested = interested;
            this.choking = choking;
            this.choked = choked;
        }

        @Override public long getUploadRate() { return uploadRate; }
        @Override public long getDownloadRate() { return downloadRate; }
        @Override public long getInactiveTime() { return inactiveTime; }
        @Override public boolean isInteresting() { return interesting; }
        @Override public boolean isInterested() { return interested; }
        @Override public boolean isChoking() { return choking; }
        @Override public boolean isChoked() { return choked; }
        @Override public boolean isConnected() { return true; }
        @Override public int completed() { return 0; }
        @Override public String toString() { return "mock-peer"; }
        @Override public PeerID getPeerID() { return null; }
        @Override public Map<String, org.klomp.snark.bencode.BEValue> getHandshakeMap() { return Collections.emptyMap(); }
        @Override public int compareTo(Peer o) { return 0; }
    }
}