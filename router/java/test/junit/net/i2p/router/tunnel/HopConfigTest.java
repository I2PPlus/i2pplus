package net.i2p.router.tunnel;

import static org.junit.Assert.*;

import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.TunnelId;

import org.junit.Test;

/**
 *  Tests for HopConfig.
 *  Pure data class - no I2P context needed.
 */
public class HopConfigTest {

    @Test
    public void testDefaultValues() {
        HopConfig cfg = new HopConfig();
        assertEquals(0, cfg.getReceiveTunnelId());
        assertNull(cfg.getReceiveTunnel());
        assertEquals(0, cfg.getSendTunnelId());
        assertNull(cfg.getSendTunnel());
        assertNull(cfg.getReceiveFrom());
        assertNull(cfg.getSendTo());
        assertNull(cfg.getLayerKey());
        assertNull(cfg.getIVKey());
        assertEquals(-1, cfg.getCreation());
        assertEquals(-1, cfg.getExpiration());
        assertEquals(0, cfg.getProcessedMessagesCount());
        assertEquals(0, cfg.getAllocatedBW());
    }

    @Test
    public void testReceiveTunnelId() {
        HopConfig cfg = new HopConfig();
        cfg.setReceiveTunnelId(12345L);
        assertEquals(12345L, cfg.getReceiveTunnelId());
        assertNotNull(cfg.getReceiveTunnel());
        assertEquals(12345L, cfg.getReceiveTunnel().getTunnelId());
    }

    @Test
    public void testReceiveTunnelIdTunnelId() {
        HopConfig cfg = new HopConfig();
        TunnelId tid = new TunnelId(99999L);
        cfg.setReceiveTunnelId(tid);
        assertEquals(99999L, cfg.getReceiveTunnelId());
        assertSame(tid, cfg.getReceiveTunnel());
    }

    @Test
    public void testSendTunnelId() {
        HopConfig cfg = new HopConfig();
        cfg.setSendTunnelId(54321L);
        assertEquals(54321L, cfg.getSendTunnelId());
        assertNotNull(cfg.getSendTunnel());
    }

    @Test
    public void testSendTunnelIdTunnelId() {
        HopConfig cfg = new HopConfig();
        TunnelId tid = new TunnelId(77777L);
        cfg.setSendTunnelId(tid);
        assertEquals(77777L, cfg.getSendTunnelId());
        assertSame(tid, cfg.getSendTunnel());
    }

    @Test
    public void testReceiveFrom() {
        HopConfig cfg = new HopConfig();
        Hash from = new Hash(new byte[32]);
        from.getData()[0] = 1;
        cfg.setReceiveFrom(from);
        assertSame(from, cfg.getReceiveFrom());
    }

    @Test
    public void testSendTo() {
        HopConfig cfg = new HopConfig();
        Hash to = new Hash(new byte[32]);
        to.getData()[0] = 2;
        cfg.setSendTo(to);
        assertSame(to, cfg.getSendTo());
    }

    @Test
    public void testLayerKey() {
        HopConfig cfg = new HopConfig();
        SessionKey key = new SessionKey();
        cfg.setLayerKey(key);
        assertSame(key, cfg.getLayerKey());
    }

    @Test
    public void testIVKey() {
        HopConfig cfg = new HopConfig();
        SessionKey key = new SessionKey();
        cfg.setIVKey(key);
        assertSame(key, cfg.getIVKey());
    }

    @Test
    public void testCreation() {
        HopConfig cfg = new HopConfig();
        long now = System.currentTimeMillis();
        cfg.setCreation(now);
        assertEquals(now, cfg.getCreation());
    }

    @Test
    public void testExpiration() {
        HopConfig cfg = new HopConfig();
        long now = System.currentTimeMillis();
        cfg.setExpiration(now + 60000);
        assertEquals(now + 60000, cfg.getExpiration());
    }

    @Test
    public void testAllocatedBW() {
        HopConfig cfg = new HopConfig();
        cfg.setAllocatedBW(1024);
        assertEquals(1024, cfg.getAllocatedBW());
    }

    @Test
    public void testIncrementProcessedMessages() {
        HopConfig cfg = new HopConfig();
        assertEquals(0, cfg.getProcessedMessagesCount());
        cfg.incrementProcessedMessages();
        assertEquals(1, cfg.getProcessedMessagesCount());
        cfg.incrementProcessedMessages();
        cfg.incrementProcessedMessages();
        assertEquals(3, cfg.getProcessedMessagesCount());
    }

    @Test
    public void testRecentMessagesCount() {
        HopConfig cfg = new HopConfig();
        assertEquals(0, cfg.getRecentMessagesCount());
        cfg.incrementProcessedMessages();
        cfg.incrementProcessedMessages();
        assertEquals(2, cfg.getRecentMessagesCount());
    }

    @Test
    public void testToString() {
        HopConfig cfg = new HopConfig();
        assertNotNull(cfg.toString());
        assertFalse(cfg.toString().isEmpty());
    }
}
