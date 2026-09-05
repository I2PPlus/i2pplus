package net.i2p.router.transport.ntcp;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.i2p.data.ByteArray;
import net.i2p.data.i2np.I2NPMessage;

import org.junit.Test;

/**
 *  Unit tests for the NTCP2 frame-sizing decisions extracted from
 *  {@link NTCPConnection#prepareNextWriteNTCP2} and
 *  {@link NTCPConnection.NTCP2ReadState#receive(ByteBuffer, long)}.
 *
 *  <p>Each decision is a pure boolean/int function, so the whole frame-packing and
 *  read-side allocation behavior is pin-able without a running connection: message
 *  contribution arithmetic, the preferred-payload cap, the pair of scheduled-block
 *  gates against the 16 KB buffer, frame-length sanity, the zero-copy shortcut, and
 *  split-frame scratch allocation.
 *
 *  @since 0.9.71+
 */
public class NTCP2FramePackingDecisionTest {

    private static final int BUFFER_SIZE = NTCPConnection.BUFFER_SIZE;
    private static final int PREFERRED_PAYLOAD_MAX = 5 * 1040;
    private static final int MAC_SIZE = OutboundNTCP2State.MAC_SIZE;

    @Test
    public void testGetMessageDataSize() {
        I2NPMessage m = mock(I2NPMessage.class);
        when(m.getMessageSize()).thenReturn(100);
        assertEquals(93, NTCPConnection.getMessageDataSize(m));
    }

    @Test
    public void testGetMessageDataSizeMinimalMessage() {
        // A message carrying only its I2NP framing contributes the framing delta.
        I2NPMessage m = mock(I2NPMessage.class);
        when(m.getMessageSize()).thenReturn(7);
        assertEquals(0, NTCPConnection.getMessageDataSize(m));
    }

    @Test
    public void testCanPackMoreMessagesAtCap() {
        assertTrue(NTCPConnection.canPackMoreMessages(PREFERRED_PAYLOAD_MAX - 93, 93));
    }

    @Test
    public void testCanPackMoreMessagesOverCap() {
        assertFalse(NTCPConnection.canPackMoreMessages(PREFERRED_PAYLOAD_MAX - 93 + 1, 93));
    }

    @Test
    public void testCanPackMoreMessagesExactCap() {
        assertTrue(NTCPConnection.canPackMoreMessages(PREFERRED_PAYLOAD_MAX, 0));
        assertFalse(NTCPConnection.canPackMoreMessages(PREFERRED_PAYLOAD_MAX, 1));
    }

    @Test
    public void testCanSendDatetimeBlockNotDue() {
        assertFalse(NTCPConnection.canSendDatetimeBlock(0, 100L, 100L - 1));
    }

    @Test
    public void testCanSendDatetimeBlockDueWhenFits() {
        assertTrue(NTCPConnection.canSendDatetimeBlock(0, 100L, 100L));
    }

    @Test
    public void testCanSendDatetimeBlockDoesNotFit() {
        int size = BUFFER_SIZE - (NTCP2Payload.BLOCK_HEADER_SIZE + 4) + 1;
        assertFalse(NTCPConnection.canSendDatetimeBlock(size, 100L, 100L));
    }

    @Test
    public void testCanSendDatetimeBlockFitsExactly() {
        int size = BUFFER_SIZE - (NTCP2Payload.BLOCK_HEADER_SIZE + 4);
        assertTrue(NTCPConnection.canSendDatetimeBlock(size, 100L, 100L));
    }

    @Test
    public void testCanSendRouterInfoBlockNotDue() {
        assertFalse(NTCPConnection.canSendRouterInfoBlock(0, Long.MAX_VALUE, 0L));
    }

    @Test
    public void testCanSendRouterInfoBlockDueWhenFits() {
        assertTrue(NTCPConnection.canSendRouterInfoBlock(BUFFER_SIZE - 1024, 100L, 100L));
    }

    @Test
    public void testCanSendRouterInfoBlockDoesNotFit() {
        assertFalse(NTCPConnection.canSendRouterInfoBlock(BUFFER_SIZE - 1024 + 1, 100L, 100L));
    }

    @Test
    public void testCanFitRouterInfoBlock() {
        assertTrue(NTCPConnection.canFitRouterInfoBlock(BUFFER_SIZE - 512, 512));
        assertFalse(NTCPConnection.canFitRouterInfoBlock(BUFFER_SIZE - 512, 513));
    }

    @Test
    public void testIsValidFrameLength() {
        assertTrue(NTCPConnection.isValidFrameLength(MAC_SIZE));
        assertFalse(NTCPConnection.isValidFrameLength(MAC_SIZE - 1));
        assertTrue(NTCPConnection.isValidFrameLength(BUFFER_SIZE));
    }

    @Test
    public void testFramePayloadLength() {
        assertEquals(0, NTCPConnection.framePayloadLength(MAC_SIZE));
        assertEquals(1, NTCPConnection.framePayloadLength(MAC_SIZE + 1));
        assertEquals(BUFFER_SIZE - MAC_SIZE, NTCPConnection.framePayloadLength(BUFFER_SIZE));
    }

    @Test
    public void testCanZeroCopyFrameAtBoundary() {
        assertTrue(NTCPConnection.canZeroCopyFrame(0, 100, 100));
    }

    @Test
    public void testCanZeroCopyFrameFrameLargerThanRemaining() {
        assertFalse(NTCPConnection.canZeroCopyFrame(0, 99, 100));
    }

    @Test
    public void testCanZeroCopyFrameMidFrame() {
        assertFalse(NTCPConnection.canZeroCopyFrame(1, 100, 100));
    }

    @Test
    public void testCanZeroCopyFrameExactRemaining() {
        assertTrue(NTCPConnection.canZeroCopyFrame(0, 100, 100));
        assertFalse(NTCPConnection.canZeroCopyFrame(0, 100, 101));
    }

    @Test
    public void testNeedsFrameBufferNullBufferAtBoundary() {
        assertTrue(NTCPConnection.needsFrameBuffer(null, 0, 50));
    }

    @Test
    public void testNeedsFrameBufferExistingBufferAtBoundary() {
        assertFalse(NTCPConnection.needsFrameBuffer(new ByteArray(new byte[64]), 0, 50));
        assertTrue(NTCPConnection.needsFrameBuffer(new ByteArray(new byte[64]), 0, 65));
        assertFalse(NTCPConnection.needsFrameBuffer(new ByteArray(new byte[64]), 0, 64));
    }

    @Test
    public void testNeedsFrameBufferMidFrameReusesBuffer() {
        assertFalse(NTCPConnection.needsFrameBuffer(null, 1, 50));
        assertFalse(NTCPConnection.needsFrameBuffer(new ByteArray(new byte[4]), 1, 50));
    }
}