package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.IOException;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketOptions;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for StandardSocket state machine fixes.
 *
 * <p>Regression tests for dev-vs-mainline bugs:
 * {@code isConnected()} always returning true after close,
 * {@code isInputShutdown()}/{@code isOutputShutdown()} returning
 * false after close, and missing double-close protection.
 *
 * @since 0.9.71+
 */
public class StandardSocketContractTest {

    @Rule public MockitoRule rule = MockitoJUnit.rule();

    @Mock private I2PSocket _i2pSocket;
    @Mock private ConnectionOptions _opts;

    private StandardSocket _socket;

    @Before
    public void setUp() {
        _socket = new StandardSocket(_i2pSocket);
        when(_i2pSocket.getOptions()).thenReturn(_opts);
        when(_opts.getInboundBufferSize()).thenReturn(64*1024);
    }

    /** getSendBufferSize() must return the same as getInboundBufferSize(). */
    @Test
    public void testGetSendBufferSizeMatchesInbound() {
        assertEquals(_socket.getReceiveBufferSize(), _socket.getSendBufferSize());
    }

    /** close() must set isClosed() to true. */
    @Test
    public void testCloseSetsClosed() throws IOException {
        assertFalse(_socket.isClosed());
        _socket.close();
        assertTrue(_socket.isClosed());
    }

    /** close() must set isConnected() to false. */
    @Test
    public void testCloseSetsDisconnected() throws IOException {
        assertTrue(_socket.isConnected());
        _socket.close();
        assertFalse(_socket.isConnected());
    }

    /** close() must set isInputShutdown() to true. */
    @Test
    public void testCloseSetsInputShutdown() throws IOException {
        assertFalse(_socket.isInputShutdown());
        _socket.close();
        assertTrue(_socket.isInputShutdown());
    }

    /** close() must set isOutputShutdown() to true. */
    @Test
    public void testCloseSetsOutputShutdown() throws IOException {
        assertFalse(_socket.isOutputShutdown());
        _socket.close();
        assertTrue(_socket.isOutputShutdown());
    }

    /** Double close must throw IOException. */
    @Test(expected = IOException.class)
    public void testDoubleCloseThrows() throws IOException {
        _socket.close();
        _socket.close();
    }

    /** shutdownInput() must set isInputShutdown() and isConnected(). */
    @Test
    public void testShutdownInputSetsFlags() throws IOException {
        assertFalse(_socket.isInputShutdown());
        assertTrue(_socket.isConnected());
        _socket.shutdownInput();
        assertTrue(_socket.isInputShutdown());
        assertFalse(_socket.isConnected());
        assertTrue(_socket.isClosed());
    }

    /** shutdownOutput() must set isOutputShutdown() and isConnected(). */
    @Test
    public void testShutdownOutputSetsFlags() throws IOException {
        assertFalse(_socket.isOutputShutdown());
        assertTrue(_socket.isConnected());
        _socket.shutdownOutput();
        assertTrue(_socket.isOutputShutdown());
        assertFalse(_socket.isConnected());
        assertTrue(_socket.isClosed());
    }

    /** isConnected() must return false after close. */
    @Test
    public void testIsConnectedAfterClose() throws IOException {
        _socket.close();
        assertFalse("isConnected() must be false after close", _socket.isConnected());
    }
}
