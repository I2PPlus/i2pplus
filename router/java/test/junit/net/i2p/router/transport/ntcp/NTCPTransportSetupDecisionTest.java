package net.i2p.router.transport.ntcp;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.i2p.data.Hash;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;

import org.junit.Test;

/**
 *  Unit tests for the connection-setup decisions extracted from
 *  {@link NTCPTransport#prepareConnectionForSending} and
 *  {@link NTCPTransport#logConnectionSetupError}.
 *
 *  <p>Covers the RouterInfo-store skip/flood gates, the version-NTCP2 early-send
 *  rule, the establishment-in-progress guard, and the benign-race classification
 *  that keeps expected IllegalStateExceptions out of the warning log. All are
 *  pure boolean functions; the message and state objects are mocked.
 *
 *  @since 0.9.71+
 */
public class NTCPTransportSetupDecisionTest {

    @Test
    public void testOwnRouterInfoStoreMatch() {
        Hash ourHash = new Hash(new byte[Hash.HASH_LENGTH]);
        DatabaseStoreMessage dsm = mock(DatabaseStoreMessage.class);
        when(dsm.getType()).thenReturn(DatabaseStoreMessage.MESSAGE_TYPE);
        when(dsm.getKey()).thenReturn(ourHash);
        assertTrue(NTCPTransport.isOwnRouterInfoStore(dsm, ourHash));
    }

    @Test
    public void testOwnRouterInfoStoreOtherKey() {
        Hash ourHash = new Hash(new byte[Hash.HASH_LENGTH]);
        Hash other = new Hash(new byte[Hash.HASH_LENGTH]);
        other.getData()[0] = 1;
        DatabaseStoreMessage dsm = mock(DatabaseStoreMessage.class);
        when(dsm.getType()).thenReturn(DatabaseStoreMessage.MESSAGE_TYPE);
        when(dsm.getKey()).thenReturn(other);
        assertFalse(NTCPTransport.isOwnRouterInfoStore(dsm, ourHash));
    }

    @Test
    public void testOwnRouterInfoStoreNonStoreMessage() {
        Hash ourHash = new Hash(new byte[Hash.HASH_LENGTH]);
        I2NPMessage m = mock(I2NPMessage.class);
        when(m.getType()).thenReturn(DataMessage.MESSAGE_TYPE);
        assertFalse(NTCPTransport.isOwnRouterInfoStore(m, ourHash));
    }

    @Test
    public void testRouterInfoStoreNoFlood() {
        DatabaseStoreMessage dsm = mock(DatabaseStoreMessage.class);
        when(dsm.getType()).thenReturn(DatabaseStoreMessage.MESSAGE_TYPE);
        when(dsm.getReplyToken()).thenReturn(0L);
        assertFalse(NTCPTransport.isRouterInfoStoreFlood(dsm));
    }

    @Test
    public void testRouterInfoStoreWithFlood() {
        DatabaseStoreMessage dsm = mock(DatabaseStoreMessage.class);
        when(dsm.getType()).thenReturn(DatabaseStoreMessage.MESSAGE_TYPE);
        when(dsm.getReplyToken()).thenReturn(1234L);
        assertTrue(NTCPTransport.isRouterInfoStoreFlood(dsm));
    }

    @Test
    public void testShouldSendInfoSkippedNotFlood() {
        assertFalse(NTCPTransport.shouldSendInfoNow(true, false, 2));
    }

    @Test
    public void testShouldSendInfoNotSkipped() {
        assertTrue(NTCPTransport.shouldSendInfoNow(false, false, 2));
        assertTrue(NTCPTransport.shouldSendInfoNow(false, true, 2));
    }

    @Test
    public void testShouldSendInfoSkippedButFlood() {
        assertTrue(NTCPTransport.shouldSendInfoNow(true, true, 2));
    }

    @Test
    public void testShouldSendInfoVersionOne() {
        // NTCP2 version 1 always sends our own RouterInfo store.
        assertTrue(NTCPTransport.shouldSendInfoNow(true, false, 1));
    }

    @Test
    public void testExpectedRaceCondition() {
        Exception e = new IllegalStateException("Unexpected prepareOutbound(), this shouldn't be called twice");
        assertTrue(NTCPTransport.isExpectedRaceCondition(e));
    }

    @Test
    public void testOtherIllegalStateNotRace() {
        assertFalse(NTCPTransport.isExpectedRaceCondition(new IllegalStateException("other reason")));
    }

    @Test
    public void testIOExceptionNotRace() {
        assertFalse(NTCPTransport.isExpectedRaceCondition(new java.io.IOException("io")));
    }

    @Test
    public void testEstablishedNullNotInProgress() {
        assertFalse(NTCPTransport.isAlreadyInProgress(null));
    }

    @Test
    public void testNonOutboundStateNotInProgress() {
        EstablishState est = mock(EstablishState.class);
        assertFalse(NTCPTransport.isAlreadyInProgress(est));
    }

    @Test
    public void testOutboundInitialStateNotInProgress() {
        OutboundNTCP2State state = mock(OutboundNTCP2State.class);
        when(state.isInitialState()).thenReturn(true);
        assertFalse(NTCPTransport.isAlreadyInProgress(state));
    }

    @Test
    public void testOutboundProgressedStateInProgress() {
        OutboundNTCP2State state = mock(OutboundNTCP2State.class);
        when(state.isInitialState()).thenReturn(false);
        assertTrue(NTCPTransport.isAlreadyInProgress(state));
    }
}