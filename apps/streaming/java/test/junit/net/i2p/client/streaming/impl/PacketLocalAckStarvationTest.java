package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the evidence-gated write-timeout ACK-starvation decision in
 * {@link PacketLocal#isAckStarvation(boolean, long, long)}.
 *
 * <p>A send that reaches its write timeout without the packet being ACKed is
 * only treated as failed when the connection made NO forward ACK progress at
 * all while it waited — the classic SlowFlush signature. If any other packet
 * was acknowledged during the window, the connection is slow but alive and the
 * send must NOT be failed (a false positive would truncate a legitimate
 * slow-but-progressing transfer). The rule mirrors the evidence-gated
 * {@code getAdaptiveSynTimeout} pattern: fail fast only on unambiguous stall.
 *
 * @since 0.9.71+
 */
public class PacketLocalAckStarvationTest {

    /** Packet ACKed -> never starvation, regardless of connection progress. */
    @Test
    public void testPacketAckedIsNeverStarvation() {
        assertFalse(PacketLocal.isAckStarvation(true, 100, 100));
        assertFalse(PacketLocal.isAckStarvation(true, 100, 50));
        assertFalse(PacketLocal.isAckStarvation(true, 0, 0));
    }

    /** No forward-ACK progress while unacked -> unambiguous starvation. */
    @Test
    public void testNoProgressIsStarvation() {
        assertTrue(PacketLocal.isAckStarvation(false, 100, 100));
        assertTrue(PacketLocal.isAckStarvation(false, 0, 0));
        assertTrue(PacketLocal.isAckStarvation(false, 42, 42));
    }

    /** Forward-ACK progress during the wait -> slow-but-alive, not starvation. */
    @Test
    public void testProgressIsNotStarvation() {
        assertFalse(PacketLocal.isAckStarvation(false, 101, 100));
        assertFalse(PacketLocal.isAckStarvation(false, 1, 0));
        assertFalse(PacketLocal.isAckStarvation(false, Long.MAX_VALUE, 0));
    }

    /** Degraded ACK (progress fell behind baseline) -> effectively starvation. */
    @Test
    public void testAckRegressionTreatedAsStarvation() {
        // ackProgressAtStart > now: the far end not only stopped but lost ground;
        // the conservative fast-fail on no-positive-progress still applies.
        assertTrue(PacketLocal.isAckStarvation(false, 99, 100));
        assertTrue(PacketLocal.isAckStarvation(false, -5, 0));
    }

    /** Boundary: exactly one unit of progress is sufficient to avoid the false positive. */
    @Test
    public void testSingleUnitProgressAvoidsFalsePositive() {
        assertFalse(PacketLocal.isAckStarvation(false, 101, 100));
        assertTrue(PacketLocal.isAckStarvation(false, 100, 100));
    }
}
