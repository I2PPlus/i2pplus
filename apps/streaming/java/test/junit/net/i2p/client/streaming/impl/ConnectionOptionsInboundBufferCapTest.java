package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for the bound applied to the router-wide inbound buffer cap,
 * {@link ConnectionOptions#inboundBufferCap(int, long)}. The cap is per inbound
 * stream, and the ceiling used to be a constant, so the same 64 MiB was allowed
 * to a 4 GiB router and to a 128 MiB one.
 */
public class ConnectionOptionsInboundBufferCapTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one. The class holds no per-test state; every case calls the static
     * decision with its own heap reading.
     *
     * @since 0.9.72+
     */
    public ConnectionOptionsInboundBufferCapTest() {}

    /**
     * A request inside both bounds is taken as written, at both ends of the
     * ladder the Tuner walks.
     */
    @Test
    public void testRequestWithinBoundsIsKept() {
        long heap = 4L * 1024 * 1024 * 1024;
        assertEquals(8 * 1024 * 1024, ConnectionOptions.inboundBufferCap(8 * 1024 * 1024, heap));
        assertEquals(64 * 1024 * 1024, ConnectionOptions.inboundBufferCap(64 * 1024 * 1024, heap));
        assertEquals(ConnectionOptions.MIN_INBOUND_BUFFER,
                     ConnectionOptions.inboundBufferCap(ConnectionOptions.MIN_INBOUND_BUFFER, heap));
    }

    /**
     * On a router with heap to spare, the absolute bound still applies, so the
     * heap share cannot buy a buffer larger than the parameter has ever allowed.
     */
    @Test
    public void testAbsoluteCeilingStillAppliesOnALargeHeap() {
        long heap = 64L * 1024 * 1024 * 1024;
        assertEquals(ConnectionOptions.MAX_INBOUND_BUFFER,
                     ConnectionOptions.inboundBufferCap(1024 * 1024 * 1024, heap));
        assertEquals(ConnectionOptions.MAX_INBOUND_BUFFER,
                     ConnectionOptions.inboundBufferCap(Integer.MAX_VALUE, heap));
    }

    /**
     * The measured case: this router runs a 4 GiB heap, and the cap sat on its
     * 64 MiB ceiling - half the heap for one inbound stream, with 1,024 stream
     * slots behind it. The heap share bounds it at 64 MiB there, which is where
     * it already was, so a large heap changes nothing.
     */
    @Test
    public void testFourGiBHeapKeepsTheAbsoluteCeiling() {
        long heap = 4L * 1024 * 1024 * 1024;
        assertEquals(64 * 1024 * 1024,
                     ConnectionOptions.inboundBufferCap(64 * 1024 * 1024, heap));
    }

    /**
     * A small router is where the constant was wrong: a 128 MiB heap allowed one
     * inbound stream to buffer half of it, so the cap now follows the heap.
     */
    @Test
    public void testSmallHeapBoundsTheCap() {
        long heap = 128L * 1024 * 1024;
        assertEquals(heap / ConnectionOptions.INBOUND_BUFFER_HEAP_SHARES,
                     ConnectionOptions.inboundBufferCap(64 * 1024 * 1024, heap));
        // 8 MiB of heap a stream may hold, on a 512 MiB heap
        heap = 512L * 1024 * 1024;
        assertEquals(8 * 1024 * 1024, ConnectionOptions.inboundBufferCap(64 * 1024 * 1024, heap));
    }

    /**
     * Below the floor the share is not the answer: a router whose heap cannot
     * cover the share still gets the smallest workable buffer, so a stream is
     * never starved below a window's worth of messages.
     */
    @Test
    public void testFloorOverridesTheShareOnATinyHeap() {
        // 16 MiB of heap would buy 256 KiB, under the floor
        long heap = 16L * 1024 * 1024;
        assertEquals(ConnectionOptions.MIN_INBOUND_BUFFER,
                     ConnectionOptions.inboundBufferCap(64 * 1024 * 1024, heap));
        assertEquals(ConnectionOptions.MIN_INBOUND_BUFFER,
                     ConnectionOptions.inboundBufferCap(1, 1024));
    }

    /**
     * A small heap binds the cap well before the floor is reached: a 64 MiB heap
     * allows 1 MiB a stream, where the constant allowed 64 MiB - the whole heap.
     */
    @Test
    public void testShareBindsAboveTheFloor() {
        long heap = 64L * 1024 * 1024;
        assertEquals(1024 * 1024, ConnectionOptions.inboundBufferCap(64 * 1024 * 1024, heap));
    }

    /**
     * A request below the floor is raised to it, whichever way the Tuner walked
     * the parameter, so a mis-set value cannot leave a stream unable to hold its
     * own window.
     */
    @Test
    public void testRequestBelowFloorIsRaised() {
        long heap = 4L * 1024 * 1024 * 1024;
        assertEquals(ConnectionOptions.MIN_INBOUND_BUFFER,
                     ConnectionOptions.inboundBufferCap(1024, heap));
        assertEquals(ConnectionOptions.MIN_INBOUND_BUFFER,
                     ConnectionOptions.inboundBufferCap(0, heap));
        assertEquals(ConnectionOptions.MIN_INBOUND_BUFFER,
                     ConnectionOptions.inboundBufferCap(-1, heap));
    }

    /**
     * A runtime that cannot report a heap - zero or less from
     * {@link net.i2p.util.SystemVersion#getMaxMemory()} - keeps the absolute
     * bound rather than collapsing every cap to the floor.
     */
    @Test
    public void testUnknownHeapKeepsTheAbsoluteBound() {
        assertEquals(64 * 1024 * 1024, ConnectionOptions.inboundBufferCap(64 * 1024 * 1024, 0));
        assertEquals(ConnectionOptions.MAX_INBOUND_BUFFER,
                     ConnectionOptions.inboundBufferCap(1024 * 1024 * 1024, 0));
        assertEquals(ConnectionOptions.MAX_INBOUND_BUFFER,
                     ConnectionOptions.inboundBufferCap(1024 * 1024 * 1024, -1));
    }

    /**
     * The bounds are consistent: the floor never exceeds the ceiling, and the
     * decision is monotonic in the request, so the Tuner's hill-climb cannot
     * oscillate between two readings of the same value.
     */
    @Test
    public void testBoundsAreConsistentAndMonotonic() {
        assertTrue(ConnectionOptions.MIN_INBOUND_BUFFER <= ConnectionOptions.MAX_INBOUND_BUFFER);
        long heap = 300L * 1024 * 1024;
        int prev = -1;
        for (int req = 0; req <= ConnectionOptions.MAX_INBOUND_BUFFER; req += 4 * 1024 * 1024) {
            int got = ConnectionOptions.inboundBufferCap(req, heap);
            assertTrue("below the floor: " + got, got >= ConnectionOptions.MIN_INBOUND_BUFFER);
            assertTrue("above the ceiling: " + got, got <= ConnectionOptions.MAX_INBOUND_BUFFER);
            assertTrue("not monotonic at request " + req, got >= prev);
            prev = got;
        }
    }
}
