package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketOptions;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests the unchoking decision logic in {@link ConnectionPacketHandler}.
 *
 * <p>Regression tests for the subsession unchoking fix:
 * <ul>
 *   <li>Contiguous big packets must unchoke</li>
 *   <li>Out-of-order packets (hole-filling) must not unchoke</li>
 *   <li>Sufficient accumulated small packets must unchoke</li>
 * </ul>
 *
 * @since 0.9.72+
 */
public class ConnectionChokeDecisionTest {

    @Rule public MockitoRule rule = MockitoJUnit.rule();

    @Mock private Connection _con;
    @Mock private MessageInputStream _inputStream;

    @Before
    public void setUp() {
        when(_con.getInputStream()).thenReturn(_inputStream);
    }

    /** Contiguous packet with payload > 512 must unchoke. */
    @Test
    public void testContiguousBigPacketUnchokes() {
        when(_inputStream.getHighestReadyBlockId()).thenReturn(9L);
        when(_inputStream.getTotalReadySize()).thenReturn(100);
        assertFalse(ConnectionPacketHandler.shouldRemainChoked(_con, 10, 600));
    }

    /** Out-of-order packet (hole-filling) must NOT unchoke unless enough data. */
    @Test
    public void testOutOfOrderPacketDoesNotUnchoke() {
        when(_inputStream.getHighestReadyBlockId()).thenReturn(10L);
        when(_inputStream.getTotalReadySize()).thenReturn(100);
        assertTrue(ConnectionPacketHandler.shouldRemainChoked(_con, 15, 600));
    }

    /** Out-of-order packet with sufficient accumulated data must unchoke. */
    @Test
    public void testOutOfOrderWithEnoughDataUnchokes() {
        when(_inputStream.getHighestReadyBlockId()).thenReturn(10L);
        when(_inputStream.getTotalReadySize()).thenReturn(600);
        assertFalse(ConnectionPacketHandler.shouldRemainChoked(_con, 15, 600));
    }

    /** Small packet (< 512) always stays choked regardless of contiguity. */
    @Test
    public void testSmallPacketStaysChoked() {
        when(_inputStream.getHighestReadyBlockId()).thenReturn(9L);
        when(_inputStream.getTotalReadySize()).thenReturn(100);
        assertTrue(ConnectionPacketHandler.shouldRemainChoked(_con, 10, 400));
    }

    /** When ready data is exactly 512, out-of-order packet unchokes. */
    @Test
    public void testExactThresholdUnchokes() {
        when(_inputStream.getHighestReadyBlockId()).thenReturn(10L);
        when(_inputStream.getTotalReadySize()).thenReturn(512);
        assertFalse(ConnectionPacketHandler.shouldRemainChoked(_con, 15, 600));
    }
}
