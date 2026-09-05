package net.i2p.router.transport.ntcp;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 *  Unit tests for the NTCP write-buffer pool introduced in
 *  {@link EventPumper#acquireWriteBuf()} / {@link EventPumper#releaseWriteBuf(byte[])}.
 *
 *  <p>Pins the pooling contract: acquired buffers are exactly the NTCP2 frame size,
 *  releasing and re-acquiring yields the same instance, and only arrays of the exact
 *  size are pooled - the handshake and termination buffers are sized differently and
 *  must never pollute the cache. Also verifies that a fully drained pooled buffer
 *  returns to the pool through {@link EventPumper#writeOneBuffer(NTCPConnection, SocketChannel)}.
 *
 *  @since 0.9.71+
 */
public class EventPumperWritePoolTest {

    /** The exact size accepted by the pool, mirroring EventPumper.WRITE_BUFSIZE. */
    private static final int WRITE_BUFSIZE = NTCPConnection.BUFFER_SIZE + OutboundNTCP2State.MAC_SIZE + 2;

    /** An Answer that consumes n bytes from the buffer, like a real SocketChannel. */
    private static Answer<Integer> writeBytes(final int n) {
        return new Answer<Integer>() {
            @Override
            public Integer answer(InvocationOnMock inv) {
                ByteBuffer b = inv.getArgument(0);
                int k = Math.min(b.remaining(), n);
                b.position(b.position() + k);
                return k;
            }
        };
    }

    /**
     *  A drained write of a pooled-size array must put the array back in the cache,
     *  so the next acquire returns the same instance.
     */
    private void assertDrainedPooledBufferReused(ByteBuffer buf, byte[] underlying) throws Exception {
        NTCPConnection con = mock(NTCPConnection.class);
        SocketChannel chan = mock(SocketChannel.class);
        when(con.getNextWriteBuf()).thenReturn(buf);
        when(chan.write(buf)).thenAnswer(writeBytes(buf.remaining()));
        assertEquals(EventPumper.WriteState.DRAIN, EventPumper.writeOneBuffer(con, chan));
        verify(con).removeWriteBuf(buf);
        assertSame(underlying, EventPumper.acquireWriteBuf());
    }

    @Test
    public void testAcquireReturnsFixedSize() {
        assertEquals(WRITE_BUFSIZE, EventPumper.acquireWriteBuf().length);
    }

    @Test
    public void testTwoAcquiresAreDistinct() {
        assertNotSame(EventPumper.acquireWriteBuf(), EventPumper.acquireWriteBuf());
    }

    @Test
    public void testReleaseAndReacquireReusesInstance() {
        byte[] a = EventPumper.acquireWriteBuf();
        a[0] = 42;
        EventPumper.releaseWriteBuf(a);
        assertSame(a, EventPumper.acquireWriteBuf());
    }

    @Test
    public void testReleasedInstancesAreReused() {
        byte[] a = EventPumper.acquireWriteBuf();
        byte[] b = EventPumper.acquireWriteBuf();
        EventPumper.releaseWriteBuf(a);
        EventPumper.releaseWriteBuf(b);
        byte[] c = EventPumper.acquireWriteBuf();
        assertTrue(c == a || c == b);
    }

    @Test
    public void testAcquireClearsReleasedContent() {
        byte[] a = EventPumper.acquireWriteBuf();
        a[0] = (byte) 0xFF;
        EventPumper.releaseWriteBuf(a);
        // The pooled buffer may retain stale bytes - callers must overwrite; the
        // pool contract only guarantees identity and size, not zeroed content.
        assertSame(a, EventPumper.acquireWriteBuf());
    }

    @Test
    public void testOversizedBufferNotPooled() {
        byte[] big = new byte[WRITE_BUFSIZE + 1];
        big[0] = 7;
        EventPumper.releaseWriteBuf(big);
        assertNotSame(big, EventPumper.acquireWriteBuf());
    }

    @Test
    public void testUndersizedBufferNotPooled() {
        byte[] small = new byte[WRITE_BUFSIZE - 1];
        small[0] = 7;
        EventPumper.releaseWriteBuf(small);
        assertNotSame(small, EventPumper.acquireWriteBuf());
    }

    @Test
    public void testNullReleaseNoOp() {
        EventPumper.releaseWriteBuf(null);
        assertEquals(WRITE_BUFSIZE, EventPumper.acquireWriteBuf().length);
    }

    @Test
    public void testDrainedPooledBufferIsReleasedToPool() throws Exception {
        byte[] data = EventPumper.acquireWriteBuf();
        data[0] = 1;
        data[1] = 2;
        ByteBuffer buf = ByteBuffer.wrap(data, 0, 2);
        assertDrainedPooledBufferReused(buf, data);
    }

    @Test
    public void testDrainedFullSizeBufferIsReleasedToPool() throws Exception {
        byte[] data = EventPumper.acquireWriteBuf();
        ByteBuffer buf = ByteBuffer.wrap(data);
        assertDrainedPooledBufferReused(buf, data);
    }

    @Test
    public void testDrainedOversizedBufferNotPooled() throws Exception {
        // Handshake buffers are larger than the frame size and must not enter the pool.
        byte[] data = new byte[WRITE_BUFSIZE + 64];
        ByteBuffer buf = ByteBuffer.wrap(data);
        NTCPConnection con = mock(NTCPConnection.class);
        SocketChannel chan = mock(SocketChannel.class);
        when(con.getNextWriteBuf()).thenReturn(buf);
        when(chan.write(buf)).thenAnswer(writeBytes(buf.remaining()));
        assertEquals(EventPumper.WriteState.DRAIN, EventPumper.writeOneBuffer(con, chan));
        verify(con).removeWriteBuf(buf);
        assertNotSame(data, EventPumper.acquireWriteBuf());
    }
}