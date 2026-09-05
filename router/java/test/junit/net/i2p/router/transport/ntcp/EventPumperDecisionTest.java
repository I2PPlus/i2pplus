package net.i2p.router.transport.ntcp;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Test;

import net.i2p.router.transport.ntcp.NTCPConnection;

/**
 *  Unit tests for the two failsafe-scan decisions extracted from
 *  {@link EventPumper#doFailsafeCheck()}:
 *  {@link EventPumper#getIdleExpire(NTCPConnection, boolean, long)} and
 *  {@link EventPumper#closeIdleOrSendRouterInfo(NTCPConnection, long, long, long)}.
 *  Pins the exact timeout branching (disposable handshakes get the short
 *  MAY_DISCON_TIMEOUT when the router is at capacity, everything else the
 *  capacity-adjusted window) and the idle-close vs. RouterInfo re-announce split,
 *  so capacity pressure drains dead handshakes without touching established peers.
 *
 *  @since 0.9.71+
 */
public class EventPumperDecisionTest {

    private static final long MAY_DISCON_TIMEOUT = 10 * 1000L;
    private static final long RI_STORE_INTERVAL = 29 * 60 * 1000L;
    private static final long NOW = 1000 * 60 * 1000L;

    /**
     *  A barely-communicative disposable inbound connection (may disconnect, no
     *  traffic) under no spare capacity is the archetypal dead handshake: it gets
     *  the short timeout so a flood of broken connections frees capacity quickly.
     */
    @Test
    public void testDisposableInboundNoCapacityGetsShortTimeout() {
        NTCPConnection con = mock(NTCPConnection.class);
        when(con.isInbound()).thenReturn(true);
        when(con.getMayDisconnect()).thenReturn(true);
        when(con.getMessagesReceived()).thenReturn(0);
        when(con.getMessagesSent()).thenReturn(0);
        assertEquals(MAY_DISCON_TIMEOUT, EventPumper.getIdleExpire(con, false, 11 * 60 * 1000L));
    }

    /**
     *  An outbound connection with no traffic is disposable regardless of capacity
     *  pressure - the outbound side was an orphaned attempt and can always go.
     */
    @Test
    public void testDisposableOutboundAlwaysShort() {
        NTCPConnection con = mock(NTCPConnection.class);
        when(con.isInbound()).thenReturn(false);
        when(con.getMayDisconnect()).thenReturn(true);
        when(con.getMessagesReceived()).thenReturn(0);
        when(con.getMessagesSent()).thenReturn(1);
        assertEquals(MAY_DISCON_TIMEOUT, EventPumper.getIdleExpire(con, true, 11 * 60 * 1000L));
    }

    /**
     *  Inbound under spare capacity, or any connection that has actually exchanged
     *  messages, is NOT disposable and keeps the capacity-adjusted window.
     */
    @Test
    public void testEstablishedConnectionKeepsWindow() {
        NTCPConnection con = mock(NTCPConnection.class);
        when(con.isInbound()).thenReturn(true);
        when(con.getMayDisconnect()).thenReturn(true);
        when(con.getMessagesReceived()).thenReturn(3);
        when(con.getMessagesSent()).thenReturn(2);
        assertEquals(9 * 60 * 1000L, EventPumper.getIdleExpire(con, false, 9 * 60 * 1000L));
    }

    /**
     *  An inbound disposable connection under spare capacity is given the benefit
     *  of the doubt and keeps the normal window instead of the short timeout.
     */
    @Test
    public void testDisposableInboundWithCapacityKeepsWindow() {
        NTCPConnection con = mock(NTCPConnection.class);
        when(con.isInbound()).thenReturn(true);
        when(con.getMayDisconnect()).thenReturn(true);
        when(con.getMessagesReceived()).thenReturn(0);
        when(con.getMessagesSent()).thenReturn(0);
        long expireWindow = 11 * 60 * 1000L;
        assertEquals(expireWindow, EventPumper.getIdleExpire(con, true, expireWindow));
    }

    /**
     *  A connection idle past its timeout is terminated; the closure is reported
     *  so the failsafe scan can count it.
     */
    @Test
    public void testIdleConnectionClosed() {
        NTCPConnection con = mock(NTCPConnection.class);
        long lastActive = NOW - 60 * 1000L;
        when(con.getLastActiveTime()).thenReturn(lastActive);
        when(con.getEstablishedOn()).thenReturn(0L);
        assertTrue(EventPumper.closeIdleOrSendRouterInfo(con, NOW, 30 * 1000L, 5 * 1000L));
        verify(con).sendTerminationAndClose();
        verify(con, never()).sendOurRouterInfo(anyBoolean());
    }

    /**
     *  A still-active connection with no established-on timestamp stays open and
     *  does nothing else.
     */
    @Test
    public void testActiveUnestablishedStaysOpen() {
        NTCPConnection con = mock(NTCPConnection.class);
        when(con.getLastActiveTime()).thenReturn(NOW - 1);
        when(con.getEstablishedOn()).thenReturn(0L);
        assertFalse(EventPumper.closeIdleOrSendRouterInfo(con, NOW, 30 * 1000L, 5 * 1000L));
        verify(con, never()).sendTerminationAndClose();
        verify(con, never()).sendOurRouterInfo(anyBoolean());
    }

    /**
     *  An established connection whose uptime has just crossed the re-announce
     *  interval gets its RouterInfo pushed (the band is one failsafe slab wide,
     *  so re-announce happens roughly once per interval per connection).
     */
    @Test
    public void testEstablishedRouterInfoDue() {
        NTCPConnection con = mock(NTCPConnection.class);
        when(con.getLastActiveTime()).thenReturn(NOW);
        long estab = NOW - RI_STORE_INTERVAL;
        when(con.getEstablishedOn()).thenReturn(estab);
        assertFalse(EventPumper.closeIdleOrSendRouterInfo(con, NOW, 30 * 1000L, 5 * 1000L));
        verify(con, never()).sendTerminationAndClose();
        verify(con).sendOurRouterInfo(false);
    }

    /**
     *  An established connection that is past the interval but inside the gap
     *  between announce bands is left alone: no close, no re-announce.
     */
    @Test
    public void testEstablishedRouterInfoGapSkipped() {
        NTCPConnection con = mock(NTCPConnection.class);
        when(con.getLastActiveTime()).thenReturn(NOW);
        long estab = NOW - RI_STORE_INTERVAL - (10 * 60 * 1000L);
        when(con.getEstablishedOn()).thenReturn(estab);
        assertFalse(EventPumper.closeIdleOrSendRouterInfo(con, NOW, 30 * 1000L, 5 * 1000L));
        verify(con, never()).sendTerminationAndClose();
        verify(con, never()).sendOurRouterInfo(anyBoolean());
    }
}