package net.i2p.router.transport.ntcp;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 *  Unit tests for {@link EventPumper#writeOneBuffer(NTCPConnection, SocketChannel)},
 *  the single-buffer step of the NTCP write drain. Pins the exact state machine
 *  governing the drain loop: empty head buffers are discarded silently, a zero-byte
 *  write never consumes the head buffer so the drain stays BLOCKED, a partial write
 *  parks the drain in BLOCKED with the buffer retained, and only a fully flushed head
 *  with no remaining data reports DRAIN (data consumed, buffer removed). Because the
 *  pumper must never spin or drop data on re-entry, the removeWriteBuf() side effects
 *  are asserted for every consuming path.
 *
 *  <p>The mock channel's {@code write} is stubbed to advance the buffer position by the
 *  returned byte count, mirroring SocketChannel semantics - the Drain/BLOCKED decision
 *  is made on the buffer's remaining() after the write, so a naive return-only stub
 *  would misclassify every outcome.
 *
 *  @since 0.9.71+
 */
public class EventPumperWriteTest {

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

    private static ByteBuffer twoBytes() {
        ByteBuffer buf = ByteBuffer.allocate(100);
        buf.put(new byte[] {1, 2});
        buf.flip();
        return buf;
    }

    @Test
    public void testEmptyQueue() throws Exception {
        NTCPConnection con = mock(NTCPConnection.class);
        SocketChannel chan = mock(SocketChannel.class);
        assertEquals(EventPumper.WriteState.EMPTY, EventPumper.writeOneBuffer(con, chan));
        verify(con, never()).removeWriteBuf(any(ByteBuffer.class));
    }

    @Test
    public void testEmptyHeadBufferDrained() throws Exception {
        NTCPConnection con = mock(NTCPConnection.class);
        SocketChannel chan = mock(SocketChannel.class);
        ByteBuffer empty = ByteBuffer.allocate(0);
        when(con.getNextWriteBuf()).thenReturn(empty);
        assertEquals(EventPumper.WriteState.DRAIN, EventPumper.writeOneBuffer(con, chan));
        verify(con).removeWriteBuf(empty);
        verify(chan, never()).write(any(ByteBuffer.class));
    }

    @Test
    public void testZeroWriteKeepsHeadBufferBlocked() throws Exception {
        NTCPConnection con = mock(NTCPConnection.class);
        SocketChannel chan = mock(SocketChannel.class);
        ByteBuffer buf = twoBytes();
        when(con.getNextWriteBuf()).thenReturn(buf);
        when(chan.write(buf)).thenAnswer(writeBytes(0));
        // A zero-byte write consumes nothing, so the head buffer still has data and
        // the drain cannot advance even if con reports an empty queue behind it.
        assertEquals(EventPumper.WriteState.BLOCKED, EventPumper.writeOneBuffer(con, chan));
        verify(con, never()).removeWriteBuf(buf);
    }

    @Test
    public void testPartialWriteBlocks() throws Exception {
        NTCPConnection con = mock(NTCPConnection.class);
        SocketChannel chan = mock(SocketChannel.class);
        ByteBuffer buf = twoBytes();
        when(con.getNextWriteBuf()).thenReturn(buf);
        when(chan.write(buf)).thenAnswer(writeBytes(1));
        assertEquals(EventPumper.WriteState.BLOCKED, EventPumper.writeOneBuffer(con, chan));
        verify(con, never()).removeWriteBuf(buf);
    }

    @Test
    public void testFullWriteDrains() throws Exception {
        NTCPConnection con = mock(NTCPConnection.class);
        SocketChannel chan = mock(SocketChannel.class);
        ByteBuffer buf = twoBytes();
        when(con.getNextWriteBuf()).thenReturn(buf);
        when(chan.write(buf)).thenAnswer(writeBytes(2));
        assertEquals(EventPumper.WriteState.DRAIN, EventPumper.writeOneBuffer(con, chan));
        verify(con).removeWriteBuf(buf);
    }

    @Test
    public void testFullWriteOnlyConsumesAvailableBytes() throws Exception {
        // Even when the channel reports writing more than remain in the buffer, the
        // buffer is fully consumed and the drain advances - matching real channel
        // behavior where write() returns at most the buffer's remaining bytes.
        NTCPConnection con = mock(NTCPConnection.class);
        SocketChannel chan = mock(SocketChannel.class);
        ByteBuffer buf = twoBytes();
        when(con.getNextWriteBuf()).thenReturn(buf);
        when(chan.write(buf)).thenAnswer(writeBytes(10));
        assertEquals(EventPumper.WriteState.DRAIN, EventPumper.writeOneBuffer(con, chan));
        verify(con).removeWriteBuf(buf);
    }
}