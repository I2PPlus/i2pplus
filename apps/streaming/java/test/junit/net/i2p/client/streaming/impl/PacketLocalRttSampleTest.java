package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the send-based RTT sample seam in
 * {@link PacketLocal#rttSample(long, long)} (see {@link PacketLocal#getRttTime()}).
 *
 * <p>RTT must be measured from the packet's <i>last transmission</i>, not from
 * creation: a packet may sit in the outbound queue (pacing, choke, slow tunnel)
 * long after creation, and folding that queueing delay into SRTT inflates it and
 * pins RTO at its ceiling exactly when recovery is needed. These tests pin the
 * pure computation behind {@code getRttTime()}.
 *
 * @since 0.9.72
 */
public class PacketLocalRttSampleTest {

    /** Normal single-send case: sample is the time between last send and ack. */
    @Test
    public void testValidSampleIsSendToAckDelta() {
        assertEquals(1000, PacketLocal.rttSample(9000, 8000));
        assertEquals(500, PacketLocal.rttSample(8500, 8000));
        assertEquals(0, PacketLocal.rttSample(8000, 8000));
    }

    /**
     * Degenerate send: the packet was never sent (lastSend unknown). A stale
     * zero lastSend must not yield a bogus sample derived from epoch 0.
     */
    @Test
    public void testUnsentPacketYieldsNoSample() {
        assertEquals(-1, PacketLocal.rttSample(9000, 0));
        assertEquals(-1, PacketLocal.rttSample(9000, -1));
    }

    /** Packet never ACKed -> no sample. */
    @Test
    public void testUnackedPacketYieldsNoSample() {
        assertEquals(-1, PacketLocal.rttSample(0, 8000));
        assertEquals(-1, PacketLocal.rttSample(-1, 8000));
    }

    /** Both times unknown -> no sample. */
    @Test
    public void testNeverTransmittedOrAckedYieldsNoSample() {
        assertEquals(-1, PacketLocal.rttSample(0, 0));
        assertEquals(-1, PacketLocal.rttSample(-1, -1));
    }

    /**
     * The sample is independent of creation time: a packet created at t=1000
     * but (due to queueing) first sent at t=8000 and ACKed at t=9000 yields a
     * 1000ms sample, not an 8000ms lifetime. This is the entire point of the
     * send-based seam vs {@link PacketLocal#getAckTime()}.
     */
    @Test
    public void testSampleExcludesQueueingDelaySinceCreation() {
        // rttSample only receives the send/ack times, so queueing time between
        // creation and first send is structurally excluded.
        assertNotEquals(8000, PacketLocal.rttSample(9000, 8000));
        assertEquals(1000, PacketLocal.rttSample(9000, 8000));
    }
}
